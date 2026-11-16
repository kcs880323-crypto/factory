// URL 앱의 다운로드 도우미. 앱이 "자기 사이트" 페이지를 다 불러온 뒤 그 페이지(맨 위 화면)에만 넣는다.
// 하는 일:
//  1) 페이지가 만든 파일(blob:, data:)을 <a download> 로 받게 하면 그 파일을 조각내 앱에 넘겨 '다운로드' 폴더에 저장한다.
//     URL.createObjectURL 을 감싸 둬서, 사이트가 클릭 직후 revokeObjectURL 로 주소를 지워도 파일을 받을 수 있다.
//  2) 다른 주소의 <a download> 는 앱의 다운로드 관리자로 받는다 (사이트가 준 파일 이름 그대로).
//  3) window.open(주소, "_blank") 는 앱 화면을 그 주소로 바꿔 버리므로, null 을 돌려주고(=팝업이 막힘)
//     잠시 뒤에 연다. 많은 사이트가 window.open 이 막히면 <a download> 로 다시 시도하는데, 그 시도가 오면 열지 않는다.
// 앱과의 통신은 앱이 실행할 때마다 새로 만드는 토큰이 있어야 하며, 그 토큰은 이 함수 안에만 있다.
(function (token) {
  "use strict";
  if (window.__urlappDownloads) return;
  window.__urlappDownloads = true;
  var bridge = window.UrlAppBridge;
  if (!bridge) return;

  var CHUNK = 786432; // 768KB: 3의 배수라 조각마다 base64 가 따로 맞아떨어진다
  var blobs = {};
  var order = [];
  var deferred = {};

  var origCreate = URL.createObjectURL;
  URL.createObjectURL = function (obj) {
    var u = origCreate.apply(URL, arguments);
    try {
      if (obj instanceof Blob) {
        blobs[u] = obj;
        order.push(u);
        if (order.length > 30) delete blobs[order.shift()];
      }
    } catch (e) { /* 무시 */ }
    return u;
  };

  function getBlob(href) {
    if (blobs[href]) return Promise.resolve(blobs[href]);
    return fetch(href).then(function (r) { return r.blob(); }); // fetch 는 바로 시작되므로 직후의 revoke 와 겹치지 않는다
  }

  function readSlice(blob, start) {
    return new Promise(function (ok, fail) {
      var r = new FileReader();
      r.onload = function () {
        var s = String(r.result);
        ok(s.substring(s.indexOf(",") + 1));
      };
      r.onerror = function () { fail(r.error); };
      r.readAsDataURL(blob.slice(start, start + CHUNK));
    });
  }

  function save(href, name) {
    cancel(href);
    getBlob(href).then(function (blob) {
      var id = bridge.begin(token, String(name || ""), String(blob.type || ""), blob.size);
      if (!id) return null;
      var pos = 0;
      function next() {
        if (pos >= blob.size) {
          bridge.end(token, id);
          return null;
        }
        return readSlice(blob, pos).then(function (b64) {
          pos += CHUNK;
          if (!bridge.chunk(token, id, b64)) return null;
          return next();
        });
      }
      return next();
    }).catch(function (e) {
      bridge.fail(token, String((e && e.message) || e));
    });
  }
  window.__urlappSave = save;

  function cancel(key) {
    if (deferred[key]) {
      clearTimeout(deferred[key]);
      delete deferred[key];
    }
  }

  function later(key, fn) {
    cancel(key);
    deferred[key] = setTimeout(function () {
      delete deferred[key];
      fn();
    }, 400);
  }

  // 사이트의 클릭 처리기가 다 돈 뒤(버블 단계, window)에 본다. 사이트가 이미 기본 동작을 막았으면 손대지 않는다.
  window.addEventListener("click", function (e) {
    if (e.defaultPrevented) return;
    var el = e.target;
    var a = el && el.closest ? el.closest("a[href]") : null;
    if (!a) return;
    var href = a.href || "";
    var scheme = href.split(":")[0].toLowerCase();
    var hasDownload = a.hasAttribute("download");
    var name = a.getAttribute("download") || "";
    if (scheme === "blob" || (scheme === "data" && hasDownload)) {
      e.preventDefault();
      save(href, name);
    } else if ((scheme === "http" || scheme === "https") && hasDownload) {
      e.preventDefault();
      cancel(href);
      bridge.download(token, href, name);
    }
  }, false);

  var origOpen = window.open;
  window.open = function (u, target) {
    var t = String(target || "_blank");
    var s;
    try {
      s = new URL(String(u == null ? "" : u), location.href).href;
    } catch (e) {
      return origOpen.apply(window, arguments);
    }
    if (t === "_self" || t === "_top" || t === "_parent") return origOpen.apply(window, arguments);
    var scheme = s.split(":")[0].toLowerCase();
    if (scheme === "blob" || scheme === "data") {
      later(s, function () { save(s, ""); });
      return null;
    }
    if (scheme === "http" || scheme === "https") {
      later(s, function () { bridge.openUrl(token, s); });
      return null;
    }
    return origOpen.apply(window, arguments);
  };
})("__URLAPP_TOKEN__");
