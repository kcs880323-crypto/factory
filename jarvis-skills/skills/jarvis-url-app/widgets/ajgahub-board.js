// AJ GA HUB(corporate-hub-2025-….run.app) 위젯: 팀 게시판 최신 글 4개(분류 · 제목, 날짜)와 오늘 새 글 수.
// 사이트가 쓰는 Supabase 표(team_board)를, 사이트에 로그인한 사용자 본인의 세션으로 읽는다 (로그인 전에는 읽지 않음).
// Supabase 주소와 공개(anon) 키는 이 파일에 적지 않고, 앱이 실행 중일 때 사이트의 스크립트에서 찾는다.
// url_app.py build … --widget widgets/ajgahub-board.js
// 주의 (2026-11-17 확인): 지금 AJ GA HUB 는 Supabase 세션을 저장하지 않아서(persistSession: false) 이 위젯은
// 로그인 토큰을 찾지 못해 아무것도 보이지 않는다. 사이트가 세션을 저장하도록 바뀌기 전에는 gaops-tasks.js 를 쓴다.
async function () {
  const CATEGORY = { achievement: "성과", story: "이야기", event: "경조사", vacation: "휴가", business_trip: "출장" };

  // 1) 로그인 세션: supabase-js 가 localStorage 의 "sb-<프로젝트>-auth-token" 에 둔다.
  let ref = null;
  let token = null;
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i);
    const m = /^sb-([a-z0-9]+)-auth-token$/.exec(k || "");
    if (!m) continue;
    try {
      const t = JSON.parse(localStorage.getItem(k)).access_token;
      if (t) { ref = m[1]; token = t; }
    } catch (e) { /* 다른 모양이면 건너뜀 */ }
  }
  if (!token) return null;

  // 2) Supabase 주소와 공개 키: 사이트 스크립트에서 한 번 찾아 둔다 (세션의 프로젝트와 같은 주소일 때만).
  if (!window.__ajWidgetCfg) {
    const src = Array.from(document.querySelectorAll('script[type="module"][src]')).map((s) => s.src)[0];
    if (!src) return null;
    const js = await (await fetch(src)).text();
    const url = `https://${ref}.supabase.co`;
    const key = (js.match(/"(eyJ[\w-]+\.[\w-]+\.[\w-]+)"/) || [])[1];
    if (!key || js.indexOf(url) < 0) return null;
    window.__ajWidgetCfg = { url, key };
  }
  const { url, key } = window.__ajWidgetCfg;

  // 3) 최신 글 (사이트의 게시판과 같은 정렬)
  const r = await fetch(
    `${url}/rest/v1/team_board?select=title,category,author_name,created_at&order=created_at.desc&limit=20`,
    { headers: { apikey: key, Authorization: `Bearer ${token}` } });
  if (!r.ok) return null; // 세션 만료 등: 사이트가 세션을 새로 받으면 다음에 다시
  const posts = await r.json();
  if (!Array.isArray(posts)) return null;

  const now = new Date();
  const day = (d) => `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
  const today = day(now);
  const yesterday = day(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1));
  const when = (iso) => {
    const d = new Date(iso);
    const k = day(d);
    return k === today ? "오늘" : k === yesterday ? "어제" : `${d.getMonth() + 1}/${d.getDate()}`;
  };
  const newToday = posts.filter((p) => day(new Date(p.created_at)) === today).length;
  return {
    title: "AJ GA HUB 팀 게시판",
    rows: posts.slice(0, 4).map((p) => ({
      label: (CATEGORY[p.category] ? CATEGORY[p.category] + " · " : "") + (p.title || "(제목 없음)"),
      value: when(p.created_at),
      tone: when(p.created_at) === "오늘" ? "pos" : "",
    })),
    note: posts.length === 0 ? "아직 글이 없어요" : newToday ? `오늘 새 글 ${newToday >= 20 ? "20+" : newToday}개` : "",
  };
}
