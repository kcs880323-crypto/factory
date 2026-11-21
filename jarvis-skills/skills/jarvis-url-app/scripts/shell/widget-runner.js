// URL 앱의 위젯 실행기. url_app.py 가 사이트별 "위젯 스크립트"(함수 하나)를 아래 자리에 넣어
// assets/urlapp-widget.js 로 만들고, 앱이 자기 사이트 페이지(맨 위 화면)에만 넣는다.
// 위젯 스크립트는 사이트 안에서 돌므로 사이트의 로그인 상태·주소를 그대로 쓴다. 돌려준 값만 앱으로 간다.
// 사이트가 window.urlappWidget(같은 모양의 함수)을 두면 그것이 앱에 넣은 스크립트보다 먼저다.
// 단, 앱의 스크립트가 인자를 하나 받으면(async function (site) {…}) 앱 스크립트가 불리고 사이트 함수(없으면 null)를
// 넘겨받는다 → 사이트의 요약을 그대로 쓰면서 줄을 덧붙일 수 있다.
//   돌려줄 값: {title: "...", rows: [{label, value, tone: "pos"|"neg"|""}] (기본 최대 4줄, 스크립트의
//   "// urlapp:rows N" 으로 8줄까지), note: "...", alt: {같은 모양}} 또는 null(갱신 안 함)
//   alt 는 두 번째 위젯(스크립트에 "// urlapp:alt-label 이름" 이 있으면 위젯 목록에 따로 생김)이 그린다.
//   두 번째 위젯의 줄 수는 "// urlapp:alt-rows N" (4줄 이하면 처음 크기 3×2, 넘으면 3×3).
//   notify: [{id, title, body, open}] 를 함께 돌려주면 앱의 알림 기능(--notify)이 처음 보는 id 만 폰 알림으로 띄운다
//   (위젯 새로고침·앱을 볼 때·15분 알림 확인 때마다 — 사이트를 고치지 않고 기존 API 로 알림을 만들 때).
// 다시 읽는 때: 페이지를 연 직후, 앱을 보는 동안 2분마다, 사이트가 무언가를 저장한 직후(POST·PUT·PATCH·DELETE fetch 가 끝난 뒤),
// 그리고 위젯 스크립트가 null(로그인 전 등)을 돌려주거나 오류가 나는 동안은 10초마다.
(function (token) {
  "use strict";
  if (window.__urlappWidget) return;
  window.__urlappWidget = true;
  var bridge = window.UrlAppBridge;
  var fn = (/*__WIDGET_FN__*/ null);
  var MAX_ROWS = /*__WIDGET_ROWS__*/4;
  var ALT_ROWS = /*__WIDGET_ALT_ROWS__*/0 || MAX_ROWS; // 두 번째 위젯의 줄 수 ("// urlapp:alt-rows N", 없으면 같게)
  if (!bridge || typeof fn !== "function") return;

  var busy = false;
  var again = false;
  var retry = null; // 아직 준비 안 됨(로그인 전 등)이면 10초 뒤 다시
  function retryLater() {
    if (retry) return;
    retry = setTimeout(function () { retry = null; run(); }, 10000);
  }
  function clip(s, n) {
    s = String(s == null ? "" : s);
    return s.length > n ? s.slice(0, n) : s;
  }
  function shape(d, max) {
    var rows = Array.isArray(d.rows) ? d.rows.slice(0, max).map(function (r) {
      return { label: clip(r && r.label, 24), value: clip(r && r.value, 24), tone: clip(r && r.tone, 3) };
    }) : [];
    return { title: clip(d.title, 30), rows: rows, note: clip(d.note, 30) };
  }
  function run() {
    if (busy) { again = true; return; }
    busy = true;
    // 사이트가 직접 위젯 함수를 주면(window.urlappWidget) 그것을 먼저 쓴다 → 사이트를 고쳐도 앱 업데이트 없이 따라간다
    var site = typeof window.urlappWidget === "function" ? window.urlappWidget : null;
    Promise.resolve().then(function () {
      if (fn.length >= 1) return fn(site ? function () { return site(); } : null); // 앱 스크립트가 사이트 요약을 받아 덧붙임
      return (site || fn)();
    }).then(function (d) {
      if (!d || typeof d !== "object") { retryLater(); return; }
      var out = shape(d, MAX_ROWS);
      if (d.alt && typeof d.alt === "object") out.alt = shape(d.alt, ALT_ROWS);
      if (Array.isArray(d.notify)) {
        out.notify = d.notify.slice(0, 20).filter(function (n) { return n && n.id != null; }).map(function (n) {
          return { id: clip(n.id, 64), title: clip(n.title, 80), body: clip(n.body, 300), open: clip(n.open, 300) };
        });
      }
      bridge.widget(token, JSON.stringify(out));
    }).catch(function () { retryLater(); /* 사이트가 바쁘거나 인터넷이 잠깐 끊김: 10초 뒤 다시 */ }).then(function () {
      busy = false;
      if (again) { again = false; setTimeout(run, 500); }
    });
  }
  window.__urlappWidgetRun = run;

  // 사이트가 저장·삭제한 직후(POST·PUT·PATCH·DELETE) 잠시 뒤 다시 읽는다.
  var timer = null;
  function soon() {
    clearTimeout(timer);
    timer = setTimeout(run, 1500);
  }
  if (window.fetch) {
    var origFetch = window.fetch;
    window.fetch = function (input, init) {
      var method = (init && init.method) || (input && typeof input === "object" && input.method) || "GET";
      var p = origFetch.apply(this, arguments);
      // 읽기(GET·HEAD)와 위젯 스크립트 자신의 요청(로그인 등, busy 동안)은 빼야 스스로 반복하지 않는다
      if (!busy && /^(POST|PUT|PATCH|DELETE)$/.test(String(method).toUpperCase())) {
        p.then(soon, function () {});
      }
      return p;
    };
  }

  run();
  setInterval(run, 120000);
  document.addEventListener("visibilitychange", function () {
    if (document.visibilityState === "visible") run();
  });
})("__URLAPP_TOKEN__");
