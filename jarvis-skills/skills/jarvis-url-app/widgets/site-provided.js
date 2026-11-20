// 범용 위젯: 사이트가 위젯 내용을 직접 정한다 (사이트를 계속 고치는 앱용 — 앱을 다시 만들지 않아도 위젯이 따라간다).
//   1) 사이트가 window.urlappWidget(함수, 같은 모양을 돌려줌)을 두면 그것이 먼저다 (위젯 실행기가 처리).
//   2) 아니면 같은 사이트의 /urlapp-widget.json 을 읽는다 (앱과 같은 로그인 쿠키, 저장(캐시) 없이). 모양:
//      {"title": "제목(30자)", "rows": [{"label": "이름(24자)", "value": "값(24자)", "tone": "pos|neg|"}], "note": "(30자)"}
//      rows 는 4줄까지 보인다. 로그인 전이면 401 을 주거나 rows 를 빼면 된다 (위젯을 바꾸지 않음).
//   3) 둘 다 없으면 서버가 응답하는지만 보인다 ("서버 연결됨") — 집 PC·Tailscale 같은 자기 서버가 켜져 있고
//      폰이 거기에 닿는지 확인용. 서버에 닿지 않으면 위젯을 바꾸지 않는다 (위젯에 '새로고침 실패 HH:mm').
// 주소는 모두 같은 사이트 안의 상대 주소라, 이 파일에는 사이트 주소가 들어가지 않는다.
// url_app.py build <URL> --widget widgets/site-provided.js
// urlapp:rows 4
// urlapp:refresh-min 10
async function () {
  if (!/^https?:$/.test(location.protocol)) return null; // 연결 오류 화면 등
  try {
    const r = await fetch("/urlapp-widget.json", { cache: "no-store", credentials: "same-origin" });
    const type = r.headers.get("content-type") || "";
    if (r.ok && type.includes("json")) {
      const d = await r.json();
      if (d && typeof d === "object" && Array.isArray(d.rows) && d.rows.length) return d;
      return null; // 사이트가 아직 줄 게 없다고 함 (로그인 전 등)
    }
    if (r.status === 401 || r.status === 403) return null;
  } catch (e) {
    return null; // 서버에 닿지 않음
  }
  // 사이트가 위젯 정보를 주지 않는다: 서버가 응답했다는 것만 보인다
  const title = String(document.title || "").trim();
  return {
    title: title,
    rows: [{ label: "서버", value: "연결됨", tone: "pos" }],
    note: "사이트가 위젯 정보를 아직 안 줌",
  };
}
