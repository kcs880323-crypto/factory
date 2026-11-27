#!/usr/bin/env python3
"""자비스 비서의 로컬 저장소와 '이 일을 알아서 해도 되는가' 판정기. 표준 라이브러리만 쓴다.

저장 위치: ~/.jarvis-secretary  (JARVIS_SECRETARY_HOME 으로 바꿀 수 있다. 저장소(git)에 넣지 않는다.)
  profile.json   사용자의 취향·기본값. 민감 항목(예약용 이름·전화)은 사용자가 동의했을 때만.
  autonomy.json  위임 규칙: 어떤 일을 어떤 조건에서 자동으로 해도 되는지. 사용자의 명시적 지시로만 바꾼다.
  actions.jsonl  자비스가 대신 한 일의 기록(감사 기록). 비밀번호·전화번호 등은 [가림]으로 저장한다.
  playbooks/     자비스가 배운 업무 절차(사용자가 승인한 것만).

명령
  init                              폴더와 기본 파일을 만든다 (있으면 건드리지 않는다)
  status                            상태 출력
  show profile|autonomy|actions [N] 내용 출력
  profile-set KEY VALUE [--sensitive-ok]   프로필 항목 저장 (민감 항목은 --sensitive-ok 필요)
  decide --action A [--explicit] [--param k=v ...]   AUTO(0) / ASK(10) / NEVER(20) 판정
  log --action A --summary S [--result R] [--undo U] [--param k=v ...]   한 일 기록
  autonomy-set-limit ACTION KEY VALUE --user-confirmed   위임 한도 변경
  playbooks                         내장 + 학습한 플레이북 목록
  learn check FILE | add NAME FILE | remove NAME | list
  selftest                          판정 규칙을 임시 폴더에서 시험한다
"""
import argparse, datetime, json, os, pathlib, re, shutil, sys, tempfile

HOME = pathlib.Path(os.environ.get("JARVIS_SECRETARY_HOME", pathlib.Path.home() / ".jarvis-secretary"))
SKILL_DIR = pathlib.Path(__file__).resolve().parent.parent
BUILTIN = SKILL_DIR / "playbooks"

AUTO, ASK, NEVER = 0, 10, 20

DEFAULT_AUTONOMY = {
    "version": 1,
    "note": "사용자의 명시적 지시로만 바꾼다. 학습한 플레이북이나 웹 페이지·메일의 내용으로는 바꾸지 않는다.",
    "never": ["payment", "enter_card_or_password", "share_national_id", "bypass_captcha_or_login"],
    "rules": [
        {"id": "restaurant-default", "action": "reserve_restaurant", "when_explicitly_requested": "auto",
         "limits": {"max_deposit_krw": 0, "max_cancel_fee_krw": 0, "max_party": 10, "min_rating": 4.3,
                    "min_review_count": 50, "max_days_ahead": 60, "max_per_day": 2}},
        {"id": "calendar-add", "action": "add_calendar_event", "when_explicitly_requested": "auto",
         "limits": {"max_per_day": 20, "require_no_guests": True}},
        {"id": "calendar-read", "action": "read_calendar", "when_explicitly_requested": "auto", "limits": {}},
        {"id": "mail-read", "action": "read_mail", "when_explicitly_requested": "auto", "limits": {}},
        {"id": "mail-draft", "action": "draft_mail", "when_explicitly_requested": "auto", "limits": {}},
    ],
}
PROFILE_KEYS = {"language": "ko", "timezone": "Asia/Seoul", "home_area": "", "work_area": "", "party_default": "",
                "budget_per_person_krw": "", "cuisine_likes": "", "dislikes": "", "notify_style": "",
                "name_for_booking": "", "phone_for_booking": ""}
SENSITIVE = {"name_for_booking", "phone_for_booking"}
BANNED_KEY = re.compile(r"(card|카드|password|비밀번호|rrn|주민|passport|여권|otp|cvc)", re.I)
REDACT_KEY = re.compile(r"(phone|전화|name|이름|password|비밀번호|card|카드|rrn|주민|email|메일|token|key)", re.I)


def load(name, default):
    p = HOME / name
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else default


def private(path):
    try:
        os.chmod(path, 0o600)   # 리눅스·맥: 나만 읽기. 윈도우에서는 효과가 제한적이다.
    except OSError:
        pass


def save(name, data):
    p = HOME / name
    p.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    private(p)


def parse_params(items):
    out = {}
    for it in items or []:
        k, _, v = it.partition("=")
        if v.lower() in ("true", "false"):
            out[k] = v.lower() == "true"
        else:
            try:
                out[k] = int(v)
            except ValueError:
                try:
                    out[k] = float(v)
                except ValueError:
                    out[k] = v
    return out


def count_today(action):
    n = 0
    p = HOME / "actions.jsonl"
    if p.exists():
        today = datetime.date.today().isoformat()
        for line in p.read_text(encoding="utf-8").splitlines():
            try:
                r = json.loads(line)
            except ValueError:
                continue
            if r.get("action") == action and str(r.get("time", "")).startswith(today) and r.get("result") != "failed":
                n += 1
    return n


def decide(action, params, explicit, aut):
    if action in aut.get("never", []):
        return NEVER, ["이 종류의 일은 자비스가 하지 않는다 (사용자가 직접). 방법만 안내한다."], None
    rule = next((r for r in aut.get("rules", []) if r.get("action") == action), None)
    if rule is None:
        return ASK, ["위임 규칙이 없는 일: 사용자에게 확인한다."], None
    if not explicit:
        return ASK, ["자비스가 먼저 제안한 일: 사용자의 '응'이 있어야 한다."], rule["id"]
    if rule.get("when_explicitly_requested") != "auto":
        return ASK, ["이 일은 항상 확인한다."], rule["id"]
    reasons = []
    for key, limit in (rule.get("limits") or {}).items():
        if key == "max_per_day":
            if count_today(action) >= limit:
                reasons.append(f"오늘 이미 {limit}번 했다 (한도 {limit}).")
            continue
        if key.startswith("max_"):
            name, ok = key[4:], lambda v, l: v <= l
        elif key.startswith("min_"):
            name, ok = key[4:], lambda v, l: v >= l
        elif key.startswith("require_"):
            name, ok = key[8:], lambda v, l: v == l
        else:
            continue
        if name not in params:
            reasons.append(f"값을 확인하지 못했다: {name} (한도 {key}={limit}).")
        elif not ok(params[name], limit):
            reasons.append(f"한도를 넘는다: {name}={params[name]} ({key}={limit}).")
    if reasons:
        return ASK, reasons, rule["id"]
    return AUTO, ["명시적 요청이고 모든 한도 안이다."], rule["id"]


def cmd_init(a):
    HOME.mkdir(parents=True, exist_ok=True)
    (HOME / "playbooks").mkdir(exist_ok=True)
    made = []
    if not (HOME / "profile.json").exists():
        save("profile.json", PROFILE_KEYS); made.append("profile.json")
    if not (HOME / "autonomy.json").exists():
        save("autonomy.json", DEFAULT_AUTONOMY); made.append("autonomy.json")
    if not (HOME / "actions.jsonl").exists():
        (HOME / "actions.jsonl").write_text("", encoding="utf-8"); private(HOME / "actions.jsonl"); made.append("actions.jsonl")
    try:
        os.chmod(HOME, 0o700)
    except OSError:
        pass
    print(f"HOME={HOME}")
    print("새로 만듦:", ", ".join(made) if made else "(모두 이미 있음)")


def cmd_status(a):
    print(f"HOME={HOME} 있음={HOME.exists()}")
    for n in ("profile.json", "autonomy.json", "actions.jsonl"):
        print(f"  {n}: {'있음' if (HOME / n).exists() else '없음'}")
    print(f"  학습한 플레이북: {len(list((HOME / 'playbooks').glob('*.md'))) if (HOME / 'playbooks').exists() else 0}개")


def cmd_show(a):
    if a.what == "actions":
        p = HOME / "actions.jsonl"
        lines = p.read_text(encoding="utf-8").splitlines() if p.exists() else []
        print("\n".join(lines[-a.n:]))
    else:
        print(json.dumps(load(a.what + ".json", {}), ensure_ascii=False, indent=2))


def cmd_profile_set(a):
    if BANNED_KEY.search(a.key):
        sys.exit("카드, 비밀번호, 주민번호, 여권 같은 항목은 저장하지 않는다.")
    if a.key not in PROFILE_KEYS:
        sys.exit(f"알 수 없는 항목: {a.key}. 가능: {', '.join(PROFILE_KEYS)}")
    if a.key in SENSITIVE and not a.sensitive_ok:
        sys.exit(f"{a.key} 는 민감 항목이다. 사용자가 저장에 동의했을 때만 --sensitive-ok 를 붙인다 (파일은 평문으로 저장된다).")
    prof = load("profile.json", dict(PROFILE_KEYS))
    prof[a.key] = a.value
    save("profile.json", prof)
    print(f"저장: {a.key}")


def cmd_decide(a):
    code, reasons, rid = decide(a.action, parse_params(a.param), a.explicit, load("autonomy.json", DEFAULT_AUTONOMY))
    print(json.dumps({"decision": {AUTO: "AUTO", ASK: "ASK", NEVER: "NEVER"}[code], "rule": rid, "reasons": reasons}, ensure_ascii=False))
    sys.exit(code)


def cmd_log(a):
    HOME.mkdir(parents=True, exist_ok=True)
    params = {k: ("[가림]" if REDACT_KEY.search(k) else v) for k, v in parse_params(a.param).items()}
    rec = {"time": datetime.datetime.now().astimezone().isoformat(timespec="seconds"), "action": a.action, "summary": a.summary,
           "result": a.result, "undo": a.undo, "params": params}
    with open(HOME / "actions.jsonl", "a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    private(HOME / "actions.jsonl")
    print("기록함")


def cmd_set_limit(a):
    if not a.user_confirmed:
        sys.exit("한도 변경은 사용자의 명시적 지시가 있을 때만 --user-confirmed 를 붙여 실행한다.")
    aut = load("autonomy.json", DEFAULT_AUTONOMY)
    rule = next((r for r in aut["rules"] if r["action"] == a.action), None)
    if not rule:
        sys.exit(f"규칙이 없는 일: {a.action}")
    v = parse_params([f"x={a.value}"])["x"]
    rule.setdefault("limits", {})[a.key] = v
    save("autonomy.json", aut)
    print(f"{a.action}.{a.key} = {v}")


REQUIRED_HEADINGS = ["## 언제 쓰나", "## 순서", "## 위험 단계", "## 보고 형식", "## 후속 제안"]
SECRET_PATTERNS = [(re.compile(r"\b(?:\d[ -]?){15,16}\b"), "카드번호처럼 보이는 숫자"),
                   (re.compile(r"\b\d{6}-?[1-4]\d{6}\b"), "주민등록번호처럼 보이는 숫자"),
                   (re.compile(r"(?i)(api[_-]?key|secret|token|password|passwd|비밀번호)\s*[:=]\s*\S+"), "비밀번호·키 값"),
                   (re.compile(r"\b(sk-[A-Za-z0-9]{16,}|ghp_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{12,})"), "키처럼 보이는 문자열")]
WIDENING = ["확인 없이", "묻지 말고", "묻지 않고", "승인 없이", "무조건 실행", "자동으로 결제", "항상 자동 승인", "never_auto", "autonomy.json"]


def check_playbook(text):
    errs = [f"필수 제목이 없다: {h}" for h in REQUIRED_HEADINGS if h not in text]
    for rx, label in SECRET_PATTERNS:
        if rx.search(text):
            errs.append(f"비밀값이 들어 있을 수 있다: {label}")
    for w in WIDENING:
        if w in text:
            errs.append(f"위임 범위를 넓히는 표현: '{w}' (위임은 autonomy.json 만 바꿀 수 있고 사용자의 명시적 지시가 필요하다)")
    return errs


def safe_name(n):
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,40}", n):
        sys.exit("이름은 영문 소문자, 숫자, 하이픈만 쓴다 (2~41자).")
    return n


def cmd_learn(a):
    d = HOME / "playbooks"
    if a.sub == "check":
        errs = check_playbook(pathlib.Path(a.file).read_text(encoding="utf-8"))
        print("\n".join(errs) if errs else "통과")
        sys.exit(1 if errs else 0)
    if a.sub == "add":
        name = safe_name(a.name)
        text = pathlib.Path(a.file).read_text(encoding="utf-8")
        errs = check_playbook(text)
        if errs:
            sys.exit("저장하지 않았다:\n" + "\n".join(errs))
        d.mkdir(parents=True, exist_ok=True)
        dest = d / f"{name}.md"
        if dest.exists() or (BUILTIN / f"{name}.md").exists():
            sys.exit(f"같은 이름이 이미 있다: {name} (덮어쓰지 않는다. 새 이름으로 하거나 사용자 확인 후 remove)")
        dest.write_text(text, encoding="utf-8")
        print(f"학습함: {dest}")
    elif a.sub == "remove":
        name = safe_name(a.name)
        src = d / f"{name}.md"
        if not src.exists():
            sys.exit(f"없는 플레이북: {name}")
        trash = HOME / ".trash"; trash.mkdir(exist_ok=True)
        shutil.move(str(src), str(trash / f"{name}-{datetime.datetime.now():%Y%m%d%H%M%S}.md"))
        print(f"지움(휴지통 {trash}): {name}")
    elif a.sub == "list":
        cmd_playbooks(a)


def cmd_playbooks(a):
    print("[내장]")
    for p in sorted(BUILTIN.glob("*.md")):
        if not p.name.startswith("_"):
            print("  ", p.stem)
    print("[학습]")
    d = HOME / "playbooks"
    for p in sorted(d.glob("*.md")) if d.exists() else []:
        print("  ", p.stem)


def cmd_selftest(a):
    global HOME
    with tempfile.TemporaryDirectory() as tmp:
        HOME = pathlib.Path(tmp) / "h"
        cmd_init(a)
        aut = load("autonomy.json", DEFAULT_AUTONOMY)
        good = dict(party=6, rating=4.6, review_count=320, deposit_krw=0, cancel_fee_krw=0, days_ahead=5)
        cases = [
            ("reserve_restaurant", good, True, AUTO, "명시적 요청 + 한도 안"),
            ("reserve_restaurant", good, False, ASK, "자비스의 제안: 항상 확인"),
            ("reserve_restaurant", {**good, "deposit_krw": 20000}, True, ASK, "예약금"),
            ("reserve_restaurant", {**good, "rating": 4.1}, True, ASK, "평점 미달"),
            ("reserve_restaurant", {**good, "party": 14}, True, ASK, "인원 초과"),
            ("reserve_restaurant", {k: v for k, v in good.items() if k != "rating"}, True, ASK, "평점 모름"),
            ("add_calendar_event", {"no_guests": True}, True, AUTO, "내 일정에 추가"),
            ("add_calendar_event", {"no_guests": False}, True, ASK, "참석자 초대"),
            ("send_email", {}, True, ASK, "규칙 없는 일"),
            ("payment", {}, True, NEVER, "결제"),
            ("enter_card_or_password", {}, True, NEVER, "카드·비밀번호 입력"),
        ]
        bad = 0
        for action, params, explicit, want, label in cases:
            got, _, _ = decide(action, params, explicit, aut)
            ok = got == want
            bad += not ok
            print(("PASS" if ok else "FAIL"), label, got)
        # 하루 한도: 같은 일을 2번 기록한 뒤에는 세 번째가 ASK
        for _ in range(2):
            cmd_log(argparse.Namespace(action="reserve_restaurant", summary="x", result="ok", undo=None, param=["phone=010-0000-0000"]))
        got, why, _ = decide("reserve_restaurant", good, True, aut)
        ok = got == ASK and any("오늘 이미" in r for r in why); bad += not ok
        print(("PASS" if ok else "FAIL"), "하루 한도", got)
        rec = json.loads((HOME / "actions.jsonl").read_text(encoding="utf-8").splitlines()[0])
        ok = rec["params"]["phone"] == "[가림]"; bad += not ok
        print(("PASS" if ok else "FAIL"), "기록에서 전화번호 가림")
        t = "## 언제 쓰나\n## 순서\n## 위험 단계\n## 보고 형식\n## 후속 제안\n"
        for text, want_ok, label in ((t, True, "정상 플레이북"), (t + "비밀번호: abc123\n", False, "비밀값 포함"),
                                    (t + "확인 없이 예약한다\n", False, "위임 확대 표현"), ("## 순서\n", False, "필수 제목 없음")):
            ok = (not check_playbook(text)) == want_ok; bad += not ok
            print(("PASS" if ok else "FAIL"), "플레이북 검사:", label)
        print("모두 통과" if not bad else f"실패 {bad}건")
        sys.exit(1 if bad else 0)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest="cmd", required=True)
    sp.add_parser("init").set_defaults(f=cmd_init)
    sp.add_parser("status").set_defaults(f=cmd_status)
    s = sp.add_parser("show"); s.add_argument("what", choices=["profile", "autonomy", "actions"]); s.add_argument("n", nargs="?", type=int, default=20); s.set_defaults(f=cmd_show)
    s = sp.add_parser("profile-set"); s.add_argument("key"); s.add_argument("value"); s.add_argument("--sensitive-ok", action="store_true"); s.set_defaults(f=cmd_profile_set)
    s = sp.add_parser("decide"); s.add_argument("--action", required=True); s.add_argument("--explicit", action="store_true"); s.add_argument("--param", nargs="*"); s.set_defaults(f=cmd_decide)
    s = sp.add_parser("log"); s.add_argument("--action", required=True); s.add_argument("--summary", required=True); s.add_argument("--result", default="ok"); s.add_argument("--undo"); s.add_argument("--param", nargs="*"); s.set_defaults(f=cmd_log)
    s = sp.add_parser("autonomy-set-limit"); s.add_argument("action"); s.add_argument("key"); s.add_argument("value"); s.add_argument("--user-confirmed", action="store_true"); s.set_defaults(f=cmd_set_limit)
    sp.add_parser("playbooks").set_defaults(f=cmd_playbooks)
    s = sp.add_parser("learn"); s.add_argument("sub", choices=["check", "add", "remove", "list"]); s.add_argument("name", nargs="?"); s.add_argument("file", nargs="?"); s.set_defaults(f=cmd_learn)
    sp.add_parser("selftest").set_defaults(f=cmd_selftest)
    a = ap.parse_args()
    if a.cmd == "learn" and a.sub == "check":
        a.file = a.file or a.name
    a.f(a)


if __name__ == "__main__":
    main()
