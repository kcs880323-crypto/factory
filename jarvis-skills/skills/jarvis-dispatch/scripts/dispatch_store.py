#!/usr/bin/env python3
"""메인 자비스가 일을 서브 세션에 맡기고, 결과를 기록하고, 다음번에 더 나은 방법을 고르게 하는 로컬 장부. 표준 라이브러리만 쓴다.

저장 위치: ~/.jarvis-dispatch  (JARVIS_DISPATCH_HOME 으로 바꿀 수 있다. 저장소(git)에 넣지 않는다. 나만 읽게 만든다.)
  events.jsonl   일이 생기고(new) 시작되고(start) 끝난(finish) 기록. 한 줄에 사건 하나, 덧붙이기만 한다.
  briefs/<id>.md 서브 세션에게 넘기는 작업 지시서

명령
  route   --kind K [--est-seconds N] [--needs-user] [--edits-files] [--long-running] [--device]
          이 일을 어디서 할지 정한다: inline(내가 바로) / subagent(백그라운드 서브에이전트) / session(백그라운드 세션, claude --bg) / ask(사용자에게 먼저 묻기).
          규칙으로 정하고, 같은 종류의 일이 3번 이상 쌓이면 지난 결과(성공률)로 더 잘 된 방법을 고른다.
  new     --kind K --goal TEXT [--route R] [--limit k=v ...] [--hours N]    일을 등록하고 작업 지시서를 만든다 (id 와 지시서 경로를 출력)
  start   ID --route R [--handle H]       시작 기록 (H: 서브에이전트 이름 또는 claude --bg 의 짧은 id)
  finish  ID --outcome success|partial|failed|cancelled [--seconds N] [--summary S]
  report  ID [FILE|-]     서브 세션의 마지막 보고(JSON 블록)를 읽어 검사하고 finish 까지 기록한다
  feedback ID --rating good|ok|bad [--note S]    사용자의 반응 기록 (학습 신호)
  list    [--status S]     show ID     scoreboard     lessons
  purge   --older-than-days N --user-confirmed     오래된 기록 삭제 (사용자 확인 필요)
  selftest

학습은 두 층이다. (1) 일을 어디서 할지 고르는 것은 기록(성공률·시간)으로 알아서 좋아진다 (권한과 무관). (2) 새 규칙·절차를 저장하는 것은 lessons 가 제안만 하고,
사용자가 전체 문장을 보고 승인해야 jarvis-skill-learner 로 저장한다. 위임 한도(권한)는 이 도구가 바꾸지 않는다.
"""
import argparse, datetime, json, os, pathlib, re, secrets, shutil, sys, tempfile

HOME = pathlib.Path(os.environ.get("JARVIS_DISPATCH_HOME", pathlib.Path.home() / ".jarvis-dispatch"))
SKILL_DIR = pathlib.Path(__file__).resolve().parent.parent
ROUTES = ("inline", "subagent", "session", "ask")
OUTCOMES = ("success", "partial", "failed", "cancelled")
MIN_SAMPLES = 3          # 같은 종류·같은 방법의 결과가 이만큼 쌓여야 기록으로 판단한다
MARGIN = 0.25            # 기본 방법보다 성공률이 이만큼 높아야 바꾼다

REDACT = [
    (re.compile(r"\b\d{2,3}[- .]?\d{3,4}[- .]?\d{4}\b"), "[전화]"),
    (re.compile(r"\b(?:\d[ -]?){13,19}\b"), "[카드번호]"),
    (re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+"), "[메일]"),
    (re.compile(r"\b(?:sk|pk|ghp|gho|xox[bap]|AKIA)[-_A-Za-z0-9]{10,}\b"), "[토큰]"),
    (re.compile(r"(?i)(password|비밀번호|passwd|secret|token)\s*[:=]\s*\S+"), r"\1=[가림]"),
]


def redact(text):
    text = str(text)
    for rx, rep in REDACT:
        text = rx.sub(rep, text)
    return text


def now():
    return datetime.datetime.now().astimezone()


def private(path):
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass


def ensure():
    (HOME / "briefs").mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(HOME, 0o700)
    except OSError:
        pass


def append(ev):
    ensure()
    ev = dict(ev, ts=now().isoformat(timespec="seconds"))
    p = HOME / "events.jsonl"
    with open(p, "a", encoding="utf-8") as f:
        f.write(json.dumps(ev, ensure_ascii=False) + "\n")
    private(p)


def events():
    p = HOME / "events.jsonl"
    if not p.exists():
        return []
    out = []
    for line in p.read_text(encoding="utf-8").splitlines():
        try:
            out.append(json.loads(line))
        except ValueError:
            continue        # 깨진 줄은 건너뛴다 (장부 전체를 막지 않는다)
    return out


def replay():
    """사건들을 처음부터 다시 읽어 일마다 현재 상태를 만든다."""
    tasks = {}
    for e in events():
        i = e.get("id")
        if e["ev"] == "new":
            tasks[i] = {"id": i, "kind": e["kind"], "goal": e["goal"], "route": e.get("route"), "status": "queued", "created": e["ts"], "limits": e.get("limits", {})}
        elif i in tasks:
            t = tasks[i]
            if e["ev"] == "start":
                t.update(status="running", route=e.get("route", t["route"]), handle=e.get("handle"), started=e["ts"])
            elif e["ev"] == "finish":
                t.update(status="done", outcome=e["outcome"], seconds=e.get("seconds"), summary=e.get("summary"), finished=e["ts"], needs_user=e.get("needs_user"))
            elif e["ev"] == "feedback":
                t.update(rating=e["rating"], note=e.get("note"))
    return tasks


def stats():
    """종류·방법별 (성공 수, 전체 수, 평균 초). 성공=success, 부분 성공은 0.5, bad 평가는 실패로 센다."""
    s = {}
    for t in replay().values():
        if t["status"] != "done" or t.get("route") not in ROUTES or t.get("outcome") == "cancelled":
            continue
        score = {"success": 1.0, "partial": 0.5, "failed": 0.0}[t["outcome"]]
        if t.get("rating") == "bad":
            score = 0.0
        k = s.setdefault((t["kind"], t["route"]), {"n": 0, "score": 0.0, "sec": 0.0, "sec_n": 0})
        k["n"] += 1
        k["score"] += score
        if t.get("seconds") is not None:
            k["sec"] += float(t["seconds"]); k["sec_n"] += 1
    return s


def default_route(a):
    """기록 없이 규칙만으로 정한 방법과 이유."""
    if a.needs_user:
        return "ask", "사용자의 선택이나 승인이 먼저 필요하다"
    est = a.est_seconds
    if a.device:
        return "session", "기기 화면 조작은 길고 여러 단계라 백그라운드 세션에서 하고, 켜 둔 시간(arm) 안에서만 한다"
    if a.long_running or est > 600:
        return "session", "10분이 넘거나 오래 걸린다: 터미널을 닫아도 계속되는 백그라운드 세션이 맞다"
    if a.edits_files:
        return "session", "파일을 고치는 일은 별도 작업 폴더(worktree)에서 하는 백그라운드 세션이 안전하다"
    if est <= 20:
        return "inline", "20초 안쪽의 가벼운 일이라 내가 바로 하는 것이 빠르다"
    return "subagent", "20초~10분의 독립된 조사·정리라 백그라운드 서브에이전트에 맡기고 대화를 이어 간다"


def choose_route(a):
    route, why = default_route(a)
    ev = {"rule": route, "history": []}
    if route in ("ask", "inline"):
        return route, why, ev
    s = stats()
    base = s.get((a.kind, route))
    best, best_rate = route, (base["score"] / base["n"] if base and base["n"] >= MIN_SAMPLES else None)
    for r in ("subagent", "session"):
        k = s.get((a.kind, r))
        if not k:
            continue
        rate = k["score"] / k["n"]
        ev["history"].append({"route": r, "n": k["n"], "success_rate": round(rate, 2), "avg_seconds": round(k["sec"] / k["sec_n"]) if k["sec_n"] else None})
        if r == route or k["n"] < MIN_SAMPLES:
            continue
        # 기본 방법의 기록이 있을 때만 비교한다 (없으면 규칙을 따른다). 기본 방법이 나쁜 기록이라면 기록이 좋은 쪽으로 옮긴다.
        if base and base["n"] >= MIN_SAMPLES and rate >= (best_rate or 0) + MARGIN and (a.device is False or r == "session"):
            best, best_rate = r, rate
    if best != route:
        why = f"규칙은 {route} 이지만 같은 종류의 지난 결과가 {best} 에서 더 잘 됐다 (성공률 {round(best_rate, 2)} vs {round(base['score'] / base['n'], 2)})"
    return best, why, ev


def cmd_route(a):
    route, why, ev = choose_route(a)
    print(json.dumps({"route": route, "reason": why, "evidence": ev}, ensure_ascii=False, indent=1))


def new_id():
    return "t-" + now().strftime("%m%d-%H%M%S") + "-" + secrets.token_hex(2)


def parse_limits(items):
    out = {}
    for it in items or []:
        if "=" not in it:
            raise SystemExit(f"--limit 은 k=v 형식입니다: {it}")
        k, v = it.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def render_brief(t, hours):
    tpl = (SKILL_DIR / "templates" / "brief.md").read_text(encoding="utf-8")
    limits = "\n".join(f"- {k}: {v}" for k, v in t["limits"].items()) or "- (따로 정한 한도 없음. 기본 위임 한도는 secretary_store.py decide 가 정한다)"
    return (tpl.replace("{{ID}}", t["id"]).replace("{{KIND}}", t["kind"]).replace("{{GOAL}}", t["goal"])
            .replace("{{LIMITS}}", limits).replace("{{HOURS}}", str(hours)).replace("{{ROUTE}}", t["route"] or "?"))


def cmd_new(a):
    goal = redact(a.goal)
    route = a.route
    if route is None:
        ns = argparse.Namespace(kind=a.kind, est_seconds=60, needs_user=False, edits_files=False, long_running=False, device=False)
        route = choose_route(ns)[0]
    if route not in ROUTES:
        raise SystemExit(f"route 는 {ROUTES} 중 하나")
    i = new_id()
    t = {"id": i, "kind": a.kind, "goal": goal, "route": route, "limits": parse_limits(a.limit)}
    append({"ev": "new", "id": i, "kind": a.kind, "goal": goal, "route": route, "limits": t["limits"]})
    ensure()
    p = HOME / "briefs" / f"{i}.md"
    p.write_text(render_brief(t, a.hours), encoding="utf-8")
    private(p)
    print(json.dumps({"id": i, "route": route, "brief": str(p)}, ensure_ascii=False))


def need_task(i):
    t = replay().get(i)
    if not t:
        raise SystemExit(f"그런 일이 없습니다: {i}")
    return t


def cmd_start(a):
    need_task(a.id)
    append({"ev": "start", "id": a.id, "route": a.route, "handle": a.handle})
    print("시작 기록:", a.id)


def cmd_finish(a):
    need_task(a.id)
    append({"ev": "finish", "id": a.id, "outcome": a.outcome, "seconds": a.seconds, "summary": redact(a.summary or "")[:500]})
    print("끝 기록:", a.id, a.outcome)


REPORT_KEYS = {"status": ("success", "partial", "failed", "needs_user"), "summary": str}


def parse_report(text):
    blocks = re.findall(r"```json\s*(\{.*?\})\s*```", text, re.S)
    if not blocks:
        raise ValueError("보고서 끝에 ```json 블록이 없습니다")
    d = json.loads(blocks[-1])
    if d.get("status") not in REPORT_KEYS["status"]:
        raise ValueError(f"status 는 {REPORT_KEYS['status']} 중 하나여야 합니다")
    if not isinstance(d.get("summary"), str) or not d["summary"].strip():
        raise ValueError("summary 가 비어 있습니다")
    if d["status"] == "needs_user" and not d.get("needs_user"):
        raise ValueError("needs_user 상태인데 사용자에게 할 질문(needs_user)이 없습니다")
    for k in ("verified", "unverified", "artifacts"):
        if k in d and not isinstance(d[k], list):
            raise ValueError(f"{k} 는 목록이어야 합니다")
    return d


def cmd_report(a):
    need_task(a.id)
    text = sys.stdin.read() if a.file in (None, "-") else pathlib.Path(a.file).read_text(encoding="utf-8")
    try:
        d = parse_report(text)
    except (ValueError, json.JSONDecodeError) as e:
        print("보고서 검사 실패:", e, file=sys.stderr)
        sys.exit(2)
    outcome = {"success": "success", "partial": "partial", "failed": "failed", "needs_user": "partial"}[d["status"]]
    t = need_task(a.id)
    secs = None
    if t.get("started"):
        secs = round((now() - datetime.datetime.fromisoformat(t["started"])).total_seconds())
    append({"ev": "finish", "id": a.id, "outcome": outcome, "seconds": secs, "summary": redact(d["summary"])[:500], "needs_user": redact(d.get("needs_user") or "") or None})
    print(json.dumps({"id": a.id, "status": d["status"], "outcome": outcome, "needs_user": d.get("needs_user"),
                      "next_suggestion": d.get("next_suggestion"), "unverified": d.get("unverified", [])}, ensure_ascii=False, indent=1))


def cmd_feedback(a):
    need_task(a.id)
    append({"ev": "feedback", "id": a.id, "rating": a.rating, "note": redact(a.note or "")[:300]})
    print("반응 기록:", a.id, a.rating)


def cmd_list(a):
    rows = sorted(replay().values(), key=lambda t: t["created"], reverse=True)
    for t in rows:
        if a.status and t["status"] != a.status:
            continue
        print(f"{t['id']}  {t['status']:<8} {t['route'] or '-':<9} {t['kind']:<14} {t.get('outcome', ''):<9} {t['goal'][:50]}")
    if not rows:
        print("(기록 없음)")


def cmd_show(a):
    print(json.dumps(need_task(a.id), ensure_ascii=False, indent=1))


def cmd_scoreboard(a):
    s = stats()
    if not s:
        print("(끝난 일이 아직 없습니다)")
    for (kind, route), k in sorted(s.items()):
        print(f"{kind:<14} {route:<9} {k['n']:>3}건  성공률 {k['score'] / k['n']:.2f}  평균 {round(k['sec'] / k['sec_n']) if k['sec_n'] else '-'}초")


def cmd_lessons(a):
    """반복된 패턴을 '제안'으로만 보여 준다. 저장은 사용자가 전체 문장을 승인한 뒤 jarvis-skill-learner 로 한다."""
    props = []
    s = stats()
    for (kind, route), k in s.items():
        if k["n"] >= MIN_SAMPLES and k["score"] / k["n"] <= 0.34:
            props.append(f"'{kind}' 일을 {route} 로 하면 {k['n']}번 중 성공률이 {k['score'] / k['n']:.2f} 입니다. 다음부터 다른 방법을 먼저 쓸까요?")
    bad = {}
    for t in replay().values():
        if t.get("rating") == "bad" and t.get("note"):
            bad.setdefault(t["kind"], []).append(t["note"])
    for kind, notes in bad.items():
        if len(notes) >= 2:
            props.append(f"'{kind}' 일에서 불만이 {len(notes)}번 있었습니다 (예: \"{notes[-1][:60]}\"). 이 점을 절차에 넣어 둘까요?")
    done = [t for t in replay().values() if t["status"] == "done"]
    kinds = {}
    for t in done:
        kinds[t["kind"]] = kinds.get(t["kind"], 0) + 1
    for kind, n in kinds.items():
        if n >= 3:
            props.append(f"'{kind}' 일을 {n}번 했습니다. 자주 쓰는 방식을 절차(플레이북)로 배워 둘까요?")
    print("\n".join(f"- {p}" for p in props) if props else "(지금 제안할 것이 없습니다)")


def cmd_purge(a):
    if not a.user_confirmed:
        raise SystemExit("사용자가 확인한 경우에만 --user-confirmed 를 붙여 실행합니다.")
    cutoff = now() - datetime.timedelta(days=a.older_than_days)
    keep = [e for e in events() if datetime.datetime.fromisoformat(e["ts"]) >= cutoff]
    removed = len(events()) - len(keep)
    ensure()
    p = HOME / "events.jsonl"
    p.write_text("".join(json.dumps(e, ensure_ascii=False) + "\n" for e in keep), encoding="utf-8")
    private(p)
    alive = {e["id"] for e in keep if e.get("id")}
    for b in (HOME / "briefs").glob("t-*.md"):
        if b.stem not in alive:
            b.unlink()
    print(f"사건 {removed}개와 지난 지시서를 지웠습니다.")


def selftest(_a):
    global HOME
    old = HOME
    HOME = pathlib.Path(tempfile.mkdtemp(prefix="dispatch-selftest-"))
    ok = fail = 0
    real_out, sys.stdout = sys.stdout, __import__("io").StringIO()      # 시험 중 명령의 출력은 숨긴다

    def check(name, cond):
        nonlocal ok, fail
        if cond:
            ok += 1
        else:
            fail += 1
            print("실패:", name, file=real_out)
    try:
        def R(kind, est=60, **kw):
            ns = dict(kind=kind, est_seconds=est, needs_user=False, edits_files=False, long_running=False, device=False); ns.update(kw)
            return choose_route(argparse.Namespace(**ns))[0]
        check("짧은 일은 inline", R("lookup", 10) == "inline")
        check("조사는 subagent", R("research", 120) == "subagent")
        check("파일을 고치면 session", R("code", 120, edits_files=True) == "session")
        check("오래 걸리면 session", R("report", 3000) == "session")
        check("기기 조작은 session", R("device", 30, device=True) == "session")
        check("사용자 결정이 먼저면 ask", R("booking", 30, needs_user=True) == "ask")
        # 지시서
        cmd_new(argparse.Namespace(kind="research", goal="강남 고기집 연락처 010-1234-5678 찾기", route="subagent", limit=["max_party=6"], hours=1))
        tid = next(iter(replay()))
        brief = (HOME / "briefs" / f"{tid}.md").read_text(encoding="utf-8")
        check("전화번호는 가려진다", "010-1234-5678" not in brief and "[전화]" in brief)
        check("지시서에 T3 금지와 보고 형식", "결제" in brief and "```json" in brief and "max_party: 6" in brief)
        check("지시서 권한이 600(리눅스·맥)", os.name == "nt" or oct((HOME / "briefs" / f'{tid}.md').stat().st_mode & 0o777) == "0o600")
        # 시작 → 보고 → 끝
        cmd_start(argparse.Namespace(id=tid, route="subagent", handle="agent-1"))
        rep = '조사 끝.\n```json\n{"status":"success","summary":"후보 3곳","verified":["평점 4.6"],"unverified":["예약 가능 여부"],"next_suggestion":"예약할까요?"}\n```'
        p = pathlib.Path(HOME / "rep.txt"); p.write_text(rep, encoding="utf-8")
        cmd_report(argparse.Namespace(id=tid, file=str(p)))
        check("보고로 끝 처리", replay()[tid]["status"] == "done" and replay()[tid]["outcome"] == "success")
        for bad in ("보고 없음", '```json\n{"status":"ok","summary":"x"}\n```', '```json\n{"status":"needs_user","summary":"x"}\n```', '```json\n{"status":"success","summary":""}\n```'):
            try:
                parse_report(bad); check("잘못된 보고를 거부: " + bad[:10], False)
            except (ValueError, json.JSONDecodeError):
                check("잘못된 보고를 거부", True)
        # 학습: 기본 방법(subagent)이 3번 실패하고 session 이 3번 성공하면 session 으로 바뀐다
        def make(kind, route, outcome):
            cmd_new(argparse.Namespace(kind=kind, goal="g", route=route, limit=[], hours=1))
            i = list(replay())[-1]
            cmd_start(argparse.Namespace(id=i, route=route, handle=None))
            cmd_finish(argparse.Namespace(id=i, outcome=outcome, seconds=100, summary="s"))
        check("기록이 없으면 규칙대로", R("booking-web", 120) == "subagent")
        for _ in range(3):
            make("booking-web", "subagent", "failed")
        check("기록이 한쪽뿐이면 규칙대로", R("booking-web", 120) == "subagent")
        for _ in range(3):
            make("booking-web", "session", "success")
        check("기록으로 session 선택", R("booking-web", 120) == "session")
        check("다른 종류는 영향 없음", R("research", 120) == "subagent")
        check("기기 일은 기록이 있어도 session", R("booking-web", 30, device=True) == "session")
        # 반응과 제안
        i = list(replay())[-1]
        cmd_feedback(argparse.Namespace(id=i, rating="bad", note="너무 오래 걸렸다 비밀번호: abc123"))
        check("반응 메모의 비밀번호는 가려진다", "abc123" not in json.dumps(events(), ensure_ascii=False))
        check("bad 반응은 실패로 센다", stats()[("booking-web", "session")]["score"] == 2.0)
        # 깨진 줄이 있어도 읽힌다
        with open(HOME / "events.jsonl", "a", encoding="utf-8") as f:
            f.write("{깨진 줄\n")
        check("깨진 줄은 건너뜀", len(replay()) >= 7)
        # purge 는 확인 필요
        try:
            cmd_purge(argparse.Namespace(older_than_days=0, user_confirmed=False)); check("확인 없이 삭제 거부", False)
        except SystemExit:
            check("확인 없이 삭제 거부", True)
        cmd_purge(argparse.Namespace(older_than_days=0, user_confirmed=True))
        check("확인하면 삭제", len(replay()) == 0)
        check("삭제 뒤에도 장부는 읽힘", isinstance(events(), list))
    finally:
        sys.stdout = real_out
        shutil.rmtree(HOME, ignore_errors=True)
        HOME = old
    print(f"selftest: {ok}개 통과, {fail}개 실패")
    sys.exit(1 if fail else 0)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest="cmd", required=True)
    s = sp.add_parser("route"); s.add_argument("--kind", required=True); s.add_argument("--est-seconds", type=int, default=60)
    for f in ("needs-user", "edits-files", "long-running", "device"):
        s.add_argument("--" + f, action="store_true")
    s.set_defaults(f=cmd_route)
    s = sp.add_parser("new"); s.add_argument("--kind", required=True); s.add_argument("--goal", required=True); s.add_argument("--route"); s.add_argument("--limit", action="append"); s.add_argument("--hours", type=float, default=1); s.set_defaults(f=cmd_new)
    s = sp.add_parser("start"); s.add_argument("id"); s.add_argument("--route", required=True, choices=ROUTES); s.add_argument("--handle"); s.set_defaults(f=cmd_start)
    s = sp.add_parser("finish"); s.add_argument("id"); s.add_argument("--outcome", required=True, choices=OUTCOMES); s.add_argument("--seconds", type=float); s.add_argument("--summary"); s.set_defaults(f=cmd_finish)
    s = sp.add_parser("report"); s.add_argument("id"); s.add_argument("file", nargs="?"); s.set_defaults(f=cmd_report)
    s = sp.add_parser("feedback"); s.add_argument("id"); s.add_argument("--rating", required=True, choices=("good", "ok", "bad")); s.add_argument("--note"); s.set_defaults(f=cmd_feedback)
    s = sp.add_parser("list"); s.add_argument("--status"); s.set_defaults(f=cmd_list)
    s = sp.add_parser("show"); s.add_argument("id"); s.set_defaults(f=cmd_show)
    sp.add_parser("scoreboard").set_defaults(f=cmd_scoreboard)
    sp.add_parser("lessons").set_defaults(f=cmd_lessons)
    s = sp.add_parser("purge"); s.add_argument("--older-than-days", type=int, required=True); s.add_argument("--user-confirmed", action="store_true"); s.set_defaults(f=cmd_purge)
    sp.add_parser("selftest").set_defaults(f=selftest)
    a = ap.parse_args()
    a.f(a)


if __name__ == "__main__":
    main()
