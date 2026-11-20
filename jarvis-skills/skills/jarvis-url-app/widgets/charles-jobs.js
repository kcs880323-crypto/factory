// CHARLES(자비스) 위젯: 지금 진행 중인 작업 요약 + 알림. 자비스 서버의 **기존 API 만 읽는다** (서버를 고치거나 다시 시작할 필요 없음).
//   /api/jobs/active → {running: [작업], unseen: [끝났는데 아직 화면에서 결과를 안 본 작업]}
//                      작업: {id, kind(chat|agent), title, message, status(running|done|error|stopped), waiting, started, next}
//   /api/job/<id>?after=N&wait=0 → 그 작업의 최근 이벤트 중 마지막 "progress"(percent·label) — 릴스 만들기 같은 오래 걸리는 도구의 예상 진행률
// 위젯: 제목 "CHARLES · 진행 중 N" → 진행 중인 작업(승인 대기 → '승인 대기', 진행률 → '62% 예상', 모르면 '진행 12분')
//       → 끝났는데 안 본 작업(완료·오류·중단). 아무것도 없으면 '진행 중인 작업 없음'.
// 알림(notify, 앱의 --notify): 승인 대기·작업 끝·오류·중단 — 앱이 처음 보는 것만 폰 알림으로 띄운다.
// 읽기만 한다: 작업을 멈추거나 '봤음'으로 바꾸지 않는다 (GET 만). 지어낸 값은 넣지 않는다 (진행률은 자비스가 적은 예상치만).
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
  let act;
  try {
    act = await get("/api/jobs/active");
  } catch (e) {
    return null; // 로그인 전·서버에 닿지 않음: 위젯을 바꾸지 않는다
  }
  const running = (Array.isArray(act.running) ? act.running : []).filter((j) => j && /^[0-9a-f]{12}$/.test(String(j.id)));
  const unseen = (Array.isArray(act.unseen) ? act.unseen : []).filter((j) => j && /^[0-9a-f]{12}$/.test(String(j.id)));
  const name = (j) => {
    const t = String(j.title || "").trim() || String(j.message || "").split("\n")[0].trim();
    return t || (j.kind === "agent" ? "실행 작업" : "대화");
  };
  const since = (s) => {
    const m = Math.max(0, Math.round((Date.now() / 1000 - Number(s || 0)) / 60));
    return m < 60 ? `${m}분` : `${Math.floor(m / 60)}시간 ${m % 60}분`;
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

  const rows = [];
  const notify = [];
  let waiting = 0;
  for (const j of running.slice(0, 4)) {
    if (j.waiting) {
      waiting++;
      rows.push({ label: name(j), value: "승인 대기", tone: "neg" });
      notify.push({ id: `${j.id}:wait`, title: "승인이 필요해요", body: name(j), open: "/" });
      continue;
    }
    const p = await lastProgress(j);
    if (p && typeof p.percent === "number") {
      rows.push({ label: p.label ? `${name(j)} · ${p.label}` : name(j), value: `${p.percent}% 예상`, tone: "pos" });
    } else {
      rows.push({ label: p && p.label ? `${name(j)} · ${p.label}` : name(j), value: `진행 ${since(j.started)}`, tone: "pos" });
    }
  }
  const END = { done: ["완료", "", "작업 끝"], error: ["오류", "neg", "작업 오류"], stopped: ["중단", "", "작업 중단"] };
  for (const j of unseen) {
    const e = END[j.status];
    if (!e) continue;
    if (rows.length < 4) rows.push({ label: name(j), value: e[0], tone: e[1] });
    notify.push({ id: `${j.id}:${j.status}`, title: e[2], body: name(j), open: "/" });
  }
  if (!rows.length) rows.push({ label: "진행 중인 작업", value: "없음" });
  const notes = [];
  if (waiting) notes.push(`승인 대기 ${waiting}`);
  if (unseen.length) notes.push(`안 본 결과 ${unseen.length}`);
  return {
    title: running.length ? `CHARLES · 진행 중 ${running.length}` : "CHARLES · 쉬는 중",
    rows,
    note: notes.join(" · "),
    notify,
  };
}
