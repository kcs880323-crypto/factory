// URL 앱의 알림 도우미 (--notify 로 만든 앱만). 앱의 웹 화면(WebView)에는 웹 알림(Notification)이 없어서,
// 사이트가 new Notification(제목, {body, tag, data: {url}}) 을 부르면 폰 알림으로 띄운다 (앱이 열려 있거나 막 내려간 동안).
// Notification.permission·requestPermission() 은 안드로이드의 알림 권한을 따른다. 알림을 누르면 앱이 열리고,
// data.url(같은 사이트 주소·경로)이 있으면 그 페이지로 간다. 앱과의 통신(window.UrlAppNotify)은 실행마다 새 토큰.
// 앱이 꺼져 있을 때의 알림은 사이트의 /urlapp-notify.json 을 앱이 15분마다 읽어 띄운다 (NotifyJob).
(function (token) {
  "use strict";
  var B = window.UrlAppNotify;
  if (!B || window.__urlappNotify) return;
  window.__urlappNotify = true;
  var waiters = [];

  function fire(target, type) {
    target.dispatchEvent(new Event(type));
  }
  function handlers(obj, types) {
    types.forEach(function (t) {
      obj["on" + t] = null;
      obj.addEventListener(t, function (e) {
        var h = obj["on" + t];
        if (typeof h === "function") h.call(obj, e);
      });
    });
  }
  // 같은 사이트의 주소만 경로로 바꾼다 (다른 사이트면 "")
  function samePath(u) {
    if (typeof u !== "string" || !u) return "";
    try {
      var x = new URL(u, location.href);
      return x.origin === location.origin ? x.pathname + x.search + x.hash : "";
    } catch (e) {
      return "";
    }
  }

  class NotificationShim extends EventTarget {
    constructor(title, options) {
      super();
      var o = options || {};
      this.title = String(title == null ? "" : title);
      this.body = String(o.body == null ? "" : o.body);
      this.tag = String(o.tag == null ? "" : o.tag);
      this.data = o.data === undefined ? null : o.data;
      this.icon = o.icon || "";
      this.silent = !!o.silent;
      handlers(this, ["show", "click", "close", "error"]);
      var self = this;
      if (B.permission(token) !== "granted") {
        setTimeout(function () { fire(self, "error"); }, 0);
        return;
      }
      var path = samePath(this.data && (this.data.url || this.data.open));
      this._key = B.show(token, this.title, this.body, this.tag, path) || "";
      setTimeout(function () { fire(self, self._key ? "show" : "error"); }, 0);
    }
    close() {
      if (this._key) B.close(token, this._key);
      fire(this, "close");
    }
    static get permission() {
      return B.permission(token);
    }
    static requestPermission(cb) {
      return new Promise(function (resolve) {
        var done = function (state) {
          if (typeof cb === "function") cb(state);
          resolve(state);
        };
        var now = B.permission(token);
        if (now !== "default") { done(now); return; }
        waiters.push(done);
        B.request(token);
      });
    }
  }
  NotificationShim.maxActions = 0;

  // 앱이 부른다 (권한 묻기 결과)
  window.__urlappNotifyPermission = function (state) {
    waiters.splice(0).forEach(function (f) { f(String(state)); });
  };
  try {
    Object.defineProperty(window, "Notification", { value: NotificationShim, configurable: true, writable: true });
  } catch (e) { /* 막힘 */ }
})("__URLAPP_TOKEN__");
