// 범용 위젯: 사이트가 위젯 내용을 직접 정한다 (사이트를 계속 고치는 앱용 — 앱을 다시 만들지 않아도 위젯이 따라간다).
//   1) 사이트가 window.urlappWidget(함수, 같은 모양을 돌려줌)을 두면 그것이 먼저다 (위젯 실행기가 처리).
//   2) 아니면 같은 사이트의 /urlapp-widget.json 을 읽는다 (앱과 같은 로그인 쿠키, 저장(캐시) 없이). 모양:
//      {"title": "제목(30자)", "rows": [{"label": "이름(24자)", "value": "값(24자)", "tone": "pos|neg|"}], "note": "(30자)"}
//      rows 는 4줄까지 보인다. 로그인 전이면 401 을 주거나 rows 를 빼면 된다 (위젯을 바꾸지 않음).
//   3) 그것도 없으면 진행 중인 작업을 스스로 찾아본다 (읽기만):
//      가) 흔한 작업 목록 주소(/api/tasks 등)의 JSON 에서 이름(title·name·task…)과 진행(progress·percent)·상태(status·state)가
//          있는 목록 → 진행 중인 것 먼저 "이름 / 60%"
//      나) 화면의 진행 막대(<progress>, role="progressbar")와 그 이름
//   4) 아무것도 못 찾으면 서버가 응답하는지만 보인다 ("서버 연결됨") — 자기 서버가 켜져 있고 폰이 닿는지 확인용.
//      서버에 닿지 않으면 위젯을 바꾸지 않는다 (위젯에 '새로고침 실패 HH:mm').
// 주소는 모두 같은 사이트 안의 상대 주소라, 이 파일에는 사이트 주소가 들어가지 않는다.
// url_app.py build <URL> --widget widgets/site-provided.js
// urlapp:rows 4
// urlapp:refresh-min 10
async function () {
  if (!/^https?:$/.test(location.protocol)) return null; // 연결 오류 화면 등
  const pageTitle = String(document.title || "").trim();
  async function getJson(path) {
    try {
      const r = await fetch(path, { cache: "no-store", credentials: "same-origin", headers: { Accept: "application/json" } });
      if (r.status === 401 || r.status === 403) return { denied: true };
      if (!r.ok || !(r.headers.get("content-type") || "").includes("json")) return null;
      return { data: await r.json() };
    } catch (e) {
      return { offline: true };
    }
  }

  // 1·2) 사이트가 정한 위젯 내용
  const own = await getJson("/urlapp-widget.json");
  if (own && own.offline) return null; // 서버에 닿지 않음
  if (own && own.denied) return null;  // 로그인 전
  if (own && own.data && typeof own.data === "object") {
    return Array.isArray(own.data.rows) && own.data.rows.length ? own.data : null;
  }

  // 3-가) 흔한 작업 목록 주소에서 진행 중인 작업 찾기
  const pick = (o, keys) => {
    for (const k of keys) if (o[k] != null && o[k] !== "" && typeof o[k] !== "object") return o[k];
    return null;
  };
  const DONE = /^(done|complete|completed|finished|success|succeeded|closed|완료|끝|종료)$/i;
  function taskRow(x) {
    const name = pick(x, ["title", "name", "task", "label", "summary", "subject", "description"]);
    const prog = pick(x, ["progress", "percent", "percentage", "pct"]);
    const status = pick(x, ["status", "state", "phase", "stage"]);
    if (name == null || (prog == null && status == null)) return null;
    let value;
    let pct = typeof prog === "number" ? prog : parseFloat(String(prog == null ? "" : prog).replace("%", ""));
    if (!isNaN(pct)) {
      if (pct > 0 && pct <= 1 && !Number.isInteger(pct)) pct *= 100;
      value = Math.round(pct) + "%";
    } else {
      value = String(status);
    }
    const done = (status != null && DONE.test(String(status).trim())) || pct >= 100;
    const failed = status != null && /fail|error|실패|오류|blocked|막힘/i.test(String(status));
    if (done) value = "완료"; // progress 1 이 '100%'인지 '1%'인지 헷갈리지 않게
    return { label: String(name).trim(), value, tone: failed ? "neg" : done ? "" : "pos", done };
  }
  function findTasks(data) {
    let best = null;
    (function walk(o, depth) {
      if (!o || depth > 4) return;
      if (Array.isArray(o)) {
        const rows = o.slice(0, 200).filter((x) => x && typeof x === "object" && !Array.isArray(x)).map(taskRow).filter(Boolean);
        if (rows.length && (!best || rows.length > best.length)) best = rows;
        o.slice(0, 50).forEach((x) => walk(x, depth + 1));
      } else if (typeof o === "object") {
        Object.keys(o).slice(0, 50).forEach((k) => walk(o[k], depth + 1));
      }
    })(data, 0);
    return best;
  }
  function summary(rows, source) {
    const active = rows.filter((r) => !r.done);
    const show = active.concat(rows.filter((r) => r.done)).slice(0, 4)
      .map((r) => ({ label: r.label, value: r.value, tone: r.tone }));
    return {
      title: (pageTitle ? pageTitle + " · " : "") + (active.length ? `진행 중 ${active.length}` : "진행 중 없음"),
      rows: show,
      note: source,
    };
  }
  for (const path of ["/api/tasks", "/api/jobs", "/api/progress", "/api/status", "/api/todos", "/api/work"]) {
    const got = await getJson(path);
    if (got && got.offline) return null;
    const rows = got && got.data ? findTasks(got.data) : null;
    if (rows && rows.length) return summary(rows, "작업 목록 " + path);
  }

  // 3-나) 화면의 진행 막대 (사이트가 화면을 그릴 시간을 조금 준다)
  function labelNear(el) {
    const own = el.getAttribute("aria-label") || el.getAttribute("title");
    if (own) return own.trim();
    let p = el.parentElement;
    for (let i = 0; i < 3 && p; i++, p = p.parentElement) {
      const t = String(p.innerText || "").replace(/\d{1,3}(\.\d+)?\s*%/g, "").replace(/\s+/g, " ").trim();
      if (t && t.length <= 60) return t;
    }
    return "";
  }
  function fromPage() {
    const rows = [];
    document.querySelectorAll("progress, [role=progressbar]").forEach((el) => {
      let v = el.tagName === "PROGRESS" ? (el.max ? (el.value / el.max) * 100 : NaN)
        : parseFloat(el.getAttribute("aria-valuenow"));
      const max = parseFloat(el.getAttribute("aria-valuemax"));
      if (el.tagName !== "PROGRESS" && !isNaN(max) && max > 0 && max !== 100) v = (v / max) * 100;
      const label = labelNear(el);
      if (!isNaN(v) && label) rows.push({ label, value: Math.round(v) + "%", tone: v >= 100 ? "" : "pos", done: v >= 100 });
    });
    return rows;
  }
  let bars = fromPage();
  for (let i = 0; i < 6 && !bars.length; i++) {
    await new Promise((r) => setTimeout(r, 500));
    bars = fromPage();
  }
  if (bars.length) return summary(bars, "화면의 진행 막대");

  // 4) 사이트가 위젯 정보를 주지 않는다: 서버가 응답했다는 것만 보인다
  return {
    title: pageTitle,
    rows: [{ label: "서버", value: "연결됨", tone: "pos" }],
    note: "사이트가 위젯 정보를 아직 안 줌",
  };
}
