// CHARLES(자비스) 위젯: 지금 하는 일 요약 + 알림.
// 0) 자비스가 위젯 정보를 직접 주면 그것이 먼저다 (2026-11-20 자비스 개발 Claude 가 연락 창구로 알려 줌):
//    GET /urlapp-widget.json → {title, rows, note} — 작업창('자비스가 하는 일')과 같은 값(진행률 83% 등, 자비스가 잰 것)
//    GET /urlapp-notify.json → {items:[…]} 가 있으면 알림은 앱이 그 목록으로 직접 띄우므로(15분 확인) 이 위젯은 알림을 만들지 않는다
//    (같은 일이 두 번 알림으로 오지 않게). 둘 다 없으면(자비스 서버가 아직 옛 코드) 아래 기존 API 로 만든다.
// 아래는 자비스 서버의 **기존 API 만 읽는** 대체 길 (서버를 고치거나 다시 시작할 필요 없음).
//   /api/jobs/active    → {running: [채팅·실행 작업], unseen: [끝났는데 아직 화면에서 결과를 안 본 작업]}
//                         작업: {id, kind(chat|agent), title, message, status(running|done|error|stopped), waiting, started, next}
//   /api/job/<id>?after=N&wait=0 → 그 작업의 최근 이벤트 중 마지막 "progress"(percent·label) — 자비스가 적은 예상 진행률
//   /api/dev/requests   → {items: [요청함: PC의 Claude Code 세션이 가져가 처리하는 음악·병맛노래·개발 요청]}
//                         요청: {id, kind(music|byeongmat|dev), text, status(queued|claimed|needs_input|done|failed|cancelled),
//                                created, claimed_at, finished_at(ISO 시각), …} — 작업창의 '지금 하는 일'
//   /api/reels/publish_info → {info: 발행 대기 영상(제목·batch_id) 또는 null} — 사람이 '허용'해야 올라간다
// 위젯 줄(4줄까지, 급한 것 먼저): 내 확인이 필요한 것(승인 대기·확인 필요·발행 대기) → 하는 중(진행률은 자비스가 적은 값만,
// 모르면 '진행 12분') → 기다리는 요청 → 끝났는데 안 본 결과. 아무것도 없으면 '지금 하는 일 없음'.
// 알림(notify, 앱의 --notify): 승인 대기·확인 필요·발행 대기·끝남·끝내지 못함 — 앱이 처음 보는 것만 폰 알림으로 띄운다.
// 읽기만 한다: 작업·요청을 멈추거나 '봤음'으로 바꾸지 않는다 (GET 만). 지어낸 값은 넣지 않는다.
// 로그인이 풀렸으면(401) 아무것도 바꾸지 않는다. 사이트 주소는 들어 있지 않다 (같은 사이트 안의 상대 주소만).
// url_app.py build <URL> --widget widgets/charles-jobs.js
// urlapp:rows 4
// urlapp:refresh-min 10
async function () {
  async function get(p) {
    const r = await fetch(p, { cache: "no-store", credentials: "same-origin", headers: { Accept: "application/json" } });
    if (r.status === 401 || r.status === 403) throw new Error("login");
    if (!r.ok || !(r.headers.get("content-type") || "").includes("json")) throw new Error("http " + r.status);
    return r.json();
  }
  const soft = (p) => get(p).catch(() => null); // 기능이 없거나 꺼져 있으면 그 부분만 뺀다
  // 0) 자비스가 주는 위젯 정보·알림 목록 (작업창과 같은 값)
  let siteWidget = null, siteNotify = false;
  try {
    const [w, n] = await Promise.all([get("/urlapp-widget.json").catch((e) => (e.message === "login" ? "login" : null)),
      soft("/urlapp-notify.json")]);
    if (w === "login") return null; // 로그인 전: 위젯을 바꾸지 않는다
    siteNotify = !!(n && Array.isArray(n.items));
    // 할 일이 없으면 자비스가 rows 를 비워 준다 (제목은 '자비스 · 쉬는 중') — 그때도 자비스 값을 쓴다
    if (w && typeof w === "object" && Array.isArray(w.rows) && typeof w.title === "string") {
      siteWidget = w.rows.length ? w : Object.assign({}, w, { rows: [{ label: "지금 하는 일", value: "없음" }] });
    }
  } catch (e) { /* 아래 대체 길 */ }
  if (siteWidget && siteNotify) {
    return { title: siteWidget.title, rows: siteWidget.rows, note: siteWidget.note || "", notify: [] };
  }
  let act;
  try {
    act = await get("/api/jobs/active");
  } catch (e) {
    return null; // 로그인 전·서버에 닿지 않음: 위젯을 바꾸지 않는다
  }
  const [devs, pub] = await Promise.all([soft("/api/dev/requests"), soft("/api/reels/publish_info")]);
  const okId = (j) => j && /^[0-9a-f]{12}$/.test(String(j.id));
  const running = (Array.isArray(act.running) ? act.running : []).filter(okId);
  const unseen = (Array.isArray(act.unseen) ? act.unseen : []).filter(okId);
  const reqs = (devs && Array.isArray(devs.items) ? devs.items : []).filter((i) => i && /^\d{1,9}$/.test(String(i.id)));

  const first = (s) => String(s || "").split("\n")[0].trim();
  const name = (j) => first(j.title) || first(j.message) || (j.kind === "agent" ? "실행 작업" : "대화");
  const KIND = { music: "음악", byeongmat: "병맛노래", dev: "개발" };
  const reqName = (i) => (i.kind === "dev" || !KIND[i.kind] ? `개발 #${i.id} · ${first(i.text).replace(/^\/개발\s*/, "")}`
    : `${KIND[i.kind]} 요청 #${i.id}`);
  const toSec = (t) => (typeof t === "number" ? t : t ? Date.parse(String(t)) / 1000 : NaN); // ISO(시간대 없으면 폰의 시각)·초 모두
  const since = (t) => {
    const s = toSec(t);
    if (isNaN(s)) return "";
    const m = Math.max(0, Math.round(Date.now() / 1000 - s) / 60 | 0);
    return m < 60 ? `${m}분` : `${Math.floor(m / 60)}시간 ${m % 60}분`;
  };
  const pctOf = (i) => { // 요청에 진행률이 적혀 있으면 (자비스가 적은 값만)
    const p = i.progress && typeof i.progress === "object" ? i.progress.percent : i.progress != null ? i.progress : i.percent;
    const n = typeof p === "number" ? p : parseFloat(p);
    return isNaN(n) ? null : Math.round(n > 0 && n <= 1 && !Number.isInteger(n) ? n * 100 : n);
  };
  async function lastProgress(j) {
    try {
      const after = Math.max(0, (Number(j.next) || 0) - 300);
      const d = await get(`/api/job/${j.id}?after=${after}&wait=0`);
      const evs = Array.isArray(d.events) ? d.events : [];
      for (let i = evs.length - 1; i >= 0; i--) {
        const e = evs[i];
        if (e && e.type === "progress") return e.clear ? null : e;
      }
    } catch (e) { /* 진행률을 못 읽으면 경과 시간만 */ }
    return null;
  }

  const act1 = [], doing = [], queued = [], ended = [], notify = [];
  let attention = 0, busy = 0;
  // 채팅·실행 작업
  for (const j of running.slice(0, 4)) {
    busy++;
    if (j.waiting) {
      attention++;
      act1.push({ label: name(j), value: "승인 대기", tone: "neg" });
      notify.push({ id: `${j.id}:wait`, title: "승인이 필요해요", body: name(j), open: "/" });
      continue;
    }
    const p = await lastProgress(j);
    const label = p && p.label ? `${name(j)} · ${p.label}` : name(j);
    doing.push({ label, value: p && typeof p.percent === "number" ? `${p.percent}% 예상` : `진행 ${since(j.started)}`, tone: "pos" });
  }
  // 요청함 (음악·병맛노래·개발 — PC의 Claude Code 세션이 처리)
  for (const i of reqs) {
    const nm = reqName(i);
    if (i.status === "claimed") {
      busy++;
      const pct = pctOf(i);
      doing.push({ label: nm, value: pct != null ? `${pct}%` : `진행 ${since(i.claimed_at || i.updated)}`, tone: "pos" });
    } else if (i.status === "needs_input") {
      attention++;
      act1.push({ label: nm, value: "확인 필요", tone: "neg" });
      notify.push({ id: `req${i.id}:needs_input`, title: "확인이 필요해요", body: nm, open: "/" });
    } else if (i.status === "queued") {
      queued.push({ label: nm, value: "대기 중" });
    } else if (i.status === "done" || i.status === "failed") {
      notify.push({ id: `req${i.id}:${i.status}`, title: i.status === "done" ? "요청 끝" : "요청을 끝내지 못했어요", body: nm, open: "/" });
    }
  }
  // 발행 대기 (사람이 '허용'해야 올라간다)
  const info = pub && pub.info && typeof pub.info === "object" ? pub.info : null;
  if (info) {
    attention++;
    const t = first(info.title) || "영상";
    act1.push({ label: `발행 대기 · ${t}`, value: "허용 필요", tone: "neg" });
    notify.push({ id: `pub:${String(info.batch_id || t).slice(0, 50)}`, title: "발행 허용이 필요해요", body: t, open: "/" });
  }
  // 끝났는데 안 본 결과
  const END = { done: ["완료", "", "작업 끝"], error: ["오류", "neg", "작업 오류"], stopped: ["중단", "", "작업 중단"] };
  for (const j of unseen) {
    const e = END[j.status];
    if (!e) continue;
    ended.push({ label: name(j), value: e[0], tone: e[1] });
    notify.push({ id: `${j.id}:${j.status}`, title: e[2], body: name(j), open: "/" });
  }

  if (siteNotify) notify.length = 0; // 알림은 자비스의 알림 목록으로 (두 번 오지 않게)
  if (siteWidget) return { title: siteWidget.title, rows: siteWidget.rows, note: siteWidget.note || "", notify };
  let rows = act1.concat(doing, queued, ended).slice(0, 4);
  if (!rows.length) rows = [{ label: "지금 하는 일", value: "없음" }];
  const notes = [];
  if (attention) notes.push(`확인 필요 ${attention}`);
  if (queued.length) notes.push(`대기 ${queued.length}`);
  if (ended.length) notes.push(`안 본 결과 ${ended.length}`);
  return {
    title: busy ? `CHARLES · 진행 중 ${busy}` : "CHARLES · 쉬는 중",
    rows,
    note: notes.join(" · "),
    notify,
  };
}
