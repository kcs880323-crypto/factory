// URL 앱의 위젯 실행기. url_app.py 가 사이트별 "위젯 스크립트"(함수 하나)를 아래 자리에 넣어
// assets/urlapp-widget.js 로 만들고, 앱이 자기 사이트 페이지(맨 위 화면)에만 넣는다.
// 위젯 스크립트는 사이트 안에서 돌므로 사이트의 로그인 상태·주소를 그대로 쓴다. 돌려준 값만 앱으로 간다.
// 사이트가 window.urlappWidget(같은 모양의 함수)을 두면 그것이 앱에 넣은 스크립트보다 먼저다.
//   돌려줄 값: {title: "...", rows: [{label, value, tone: "pos"|"neg"|""}] (최대 4줄), note: "..."} 또는 null(갱신 안 함)
// 다시 읽는 때: 페이지를 연 직후, 앱을 보는 동안 2분마다, 사이트가 무언가를 저장한 직후(POST·PUT·PATCH·DELETE fetch 가 끝난 뒤),
// 그리고 위젯 스크립트가 null(로그인 전 등)을 돌려주는 동안은 10초마다.
(function (token) {
  "use strict";
  if (window.__urlappWidget) return;
  window.__urlappWidget = true;
  var bridge = window.UrlAppBridge;
  var fn = (/*__WIDGET_FN__*/ null);
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
  function run() {
    if (busy) { again = true; return; }
    busy = true;
    // 사이트가 직접 위젯 함수를 주면(window.urlappWidget) 그것을 먼저 쓴다 → 사이트를 고쳐도 앱 업데이트 없이 따라간다
    var site = typeof window.urlappWidget === "function" ? window.urlappWidget : null;
    Promise.resolve().then(function () { return (site || fn)(); }).then(function (d) {
      if (!d || typeof d !== "object") { retryLater(); return; }
      var rows = Array.isArray(d.rows) ? d.rows.slice(0, 4).map(function (r) {
        return { label: clip(r && r.label, 24), value: clip(r && r.value, 24), tone: clip(r && r.tone, 3) };
      }) : [];
      bridge.widget(token, JSON.stringify({ title: clip(d.title, 30), rows: rows, note: clip(d.note, 30) }));
    }).catch(function () { /* 사이트가 바쁘거나 로그인 전: 다음에 다시 */ }).then(function () {
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
