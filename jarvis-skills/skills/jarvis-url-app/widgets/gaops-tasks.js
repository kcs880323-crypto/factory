// AJ GA HUB 위젯: GA Ops(담당자 과업현황, 과업 관리)의 "진행 중" 과업.
// GA Ops 는 Firebase(Firestore)의 tasks 컬렉션에 과업을 두고 익명 로그인으로 읽는다. 이 위젯도 같은 방식으로 읽는다
// (GA Ops 페이지를 연 사람이 볼 수 있는 것과 같은 범위). AJ GA HUB 에 로그인한 뒤에만 읽는다.
// AJ GA HUB 는 로그인 정보를 저장하지 않는다(Supabase persistSession: false, 메모리에만). 그래서 로그인 여부는
// 화면으로 본다: 로그인한 뒤에만 나오는 "로그아웃" 버튼(title="로그아웃")이 있으면 로그인한 것.
// 프로젝트 id 와 웹 API 키(GA Ops 페이지 스크립트에 있는 공개 값)는 이 파일에 적지 않고 빌드할 때 넣는다:
//   url_app.py build … --widget widgets/gaops-tasks.js \
//       --widget-param GAOPS_PROJECT=<프로젝트 id> --widget-param GAOPS_API_KEY=<웹 API 키>
// 카드와 같은 기준: 진척율 = manualProgress, 마감 임박 = 종료일까지 0~3일, 경과 = 종료일 지남.
// 새로고침 (2026-11-21): 위젯의 새로고침 단추와 10분마다 자동 새로고침(화면이 켜져 있을 때). 앱을 열지 않고 보이지 않는 웹 화면으로 읽는데
// AJ GA HUB 는 로그인을 저장하지 않아 그 화면은 늘 로그아웃 상태다 → 앱 화면에서 로그인한 것을 이 폰 앱 안(localStorage)에 시각만 적어 두고,
// 그 뒤 7일 안에는 로그인 화면이 아니어도 읽는다 (7일이 지나면 앱을 열어 한 번 로그인해야 다시 갱신). 적는 것은 시각뿐(이름·비밀번호 없음).
// urlapp:refresh-min 10
async function () {
  const PROJECT = "__PARAM_GAOPS_PROJECT__";
  const KEY = "__PARAM_GAOPS_API_KEY__";
  const TITLE = "GA Ops 진행 중 과업";

  // 0) AJ GA HUB 로그인 확인: 로그아웃 버튼이 보이면 로그인한 것 (세션을 저장하도록 사이트가 바뀌면 그것도 인정)
  let loggedIn = !!document.querySelector('[title="로그아웃"]');
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i) || "";
    if (!/^sb-[a-z0-9]+-auth-token$/.test(k)) continue;
    try { if (JSON.parse(localStorage.getItem(k)).access_token) loggedIn = true; } catch (e) { /* 건너뜀 */ }
  }
  const SEEN = "urlapp_gaops_login_at";
  const KEEP_MS = 7 * 86400000;
  const nowMs = Date.now();
  if (loggedIn) {
    localStorage.setItem(SEEN, String(nowMs));
  } else {
    const seen = Number(localStorage.getItem(SEEN) || 0);
    if (!(seen > nowMs - KEEP_MS && seen <= nowMs)) return null; // 로그인한 적이 없거나 7일이 지남: 앱을 열어 로그인할 때까지 바꾸지 않음
  }
  const fail = (why) => ({ title: TITLE, rows: [], note: `GA Ops 연결 실패 (${why})` });

  // 1) Firebase 익명 로그인. 토큰은 이 앱 안(localStorage)에만 두고, 만료 전에 갱신한다 (매번 새 익명 사용자를 만들지 않게).
  const AUTH = "urlapp_gaops_auth";
  let auth = null;
  try { auth = JSON.parse(localStorage.getItem(AUTH) || "null"); } catch (e) { auth = null; }
  const now = Date.now();
  if (!auth || !auth.idToken || !(auth.exp > now + 60000)) {
    if (auth && auth.refreshToken) {
      const r = await fetch(`https://securetoken.googleapis.com/v1/token?key=${KEY}`, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: `grant_type=refresh_token&refresh_token=${encodeURIComponent(auth.refreshToken)}`,
      });
      if (r.ok) {
        const j = await r.json();
        auth = { idToken: j.id_token, refreshToken: j.refresh_token, exp: now + (Number(j.expires_in) || 3600) * 1000 };
      } else {
        auth = null;
      }
    }
    if (!auth || !auth.idToken) {
      const r = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${KEY}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ returnSecureToken: true }),
      });
      if (!r.ok) return fail(`로그인 ${r.status}`);
      const j = await r.json();
      auth = { idToken: j.idToken, refreshToken: j.refreshToken, exp: now + (Number(j.expiresIn) || 3600) * 1000 };
    }
    localStorage.setItem(AUTH, JSON.stringify(auth));
  }

  // 2) tasks 읽기 (필요한 칸만, 읽기 전용 GET)
  const fields = ["title", "status", "priority", "manualProgress", "dueDate", "mainAssigneeName"];
  const base = `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/(default)/documents/tasks`;
  const docs = [];
  let pageToken = "";
  for (let page = 0; page < 5; page++) {
    const q = new URLSearchParams({ pageSize: "300", key: KEY });
    fields.forEach((f) => q.append("mask.fieldPaths", f));
    if (pageToken) q.set("pageToken", pageToken);
    const r = await fetch(`${base}?${q}`, { headers: { Authorization: `Bearer ${auth.idToken}` } });
    if (r.status === 401 || r.status === 403) {
      localStorage.removeItem(AUTH); // 다음에 새로 로그인
      return fail(`읽기 ${r.status}`);
    }
    if (!r.ok) return fail(`읽기 ${r.status}`);
    const j = await r.json();
    (j.documents || []).forEach((d) => docs.push(d.fields || {}));
    if (!j.nextPageToken) break;
    pageToken = j.nextPageToken;
  }

  // 3) 진행 중만, 급한 순 (경과 → 마감 가까운 순 → 종료일 없음, 같으면 우선순위 높음 먼저)
  const val = (f) => (!f ? undefined : "stringValue" in f ? f.stringValue : "integerValue" in f ? Number(f.integerValue)
    : "doubleValue" in f ? f.doubleValue : undefined);
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const daysLeft = (s) => {
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(s || "");
    return m ? Math.round((new Date(+m[1], +m[2] - 1, +m[3]) - today) / 86400000) : null;
  };
  const tasks = docs.map((f) => ({
    title: val(f.title) || "(제목 없음)",
    status: val(f.status),
    high: val(f.priority) === "높음",
    progress: Math.round(Number(val(f.manualProgress)) || 0),
    left: daysLeft(val(f.dueDate)),
    who: val(f.mainAssigneeName) || "",
  })).filter((t) => t.status === "진행 중");
  const key = (t) => (t.left == null ? 1e9 : t.left);
  tasks.sort((a, b) => key(a) - key(b) || Number(b.high) - Number(a.high));
  const overdue = tasks.filter((t) => t.left != null && t.left < 0).length;
  const soon = tasks.filter((t) => t.left != null && t.left >= 0 && t.left <= 3).length;
  const dday = (n) => (n == null ? null : n < 0 ? `D+${-n}` : n === 0 ? "D-day" : `D-${n}`);
  return {
    title: `${TITLE} ${tasks.length}건`,
    rows: tasks.slice(0, 4).map((t) => ({
      label: t.title + (t.who ? ` · ${t.who}` : ""),
      value: [dday(t.left), `${t.progress}%`].filter(Boolean).join(" · "),
      tone: t.left != null && t.left <= 3 ? "neg" : "",
    })),
    note: tasks.length === 0 ? "진행 중인 과업이 없어요"
      : [overdue ? `경과 ${overdue}` : "", soon ? `마감 임박 ${soon}` : ""].filter(Boolean).join(" · "),
  };
}
