// URL 앱의 음성 도우미 (--mic 로 만든 앱만). 앱의 웹 화면(WebView)에는 웹 음성 기능이 없거나 동작하지 않아서,
// SpeechRecognition·webkitSpeechRecognition·speechSynthesis·SpeechSynthesisUtterance 를 안드로이드의 음성 인식·읽어 주기로
// 바꿔 넣는다 → 크롬용으로 만든 사이트의 음성 기능이 앱에서도 그대로 돈다. 앱이 자기 사이트의 맨 위 화면에만 넣고,
// 앱과의 통신(window.UrlAppSpeech)은 실행할 때마다 새로 만드는 토큰이 있어야 한다.
// 사이트는 음성 기능을 쓸 때(누를 때) window.SpeechRecognition || window.webkitSpeechRecognition 을 확인해야 한다
// (페이지를 처음 읽을 때 한 번만 확인하면, 이 도우미가 들어가기 전이라 '지원 안 함'으로 볼 수 있다).
(function (token) {
  "use strict";
  var B = window.UrlAppSpeech;
  if (!B || window.__urlappSpeech) return;
  window.__urlappSpeech = true;

  var active = null; // 지금 듣는 SpeechRecognition
  var utter = {};    // 읽는 중·기다리는 것: id → utterance
  var queue = 0;
  var seq = 0;

  function fire(target, type, props) {
    var ev = new Event(type);
    if (props) {
      Object.keys(props).forEach(function (k) {
        try { Object.defineProperty(ev, k, { value: props[k], enumerable: true }); } catch (e) { /* 이미 있는 이름 */ }
      });
    }
    target.dispatchEvent(ev);
  }
  // 우리가 만든 객체의 onxxx 처리기: addEventListener 로 이어 둔다 (dispatchEvent 한 번으로 둘 다 불린다)
  function handlers(obj, types) {
    types.forEach(function (t) {
      obj["on" + t] = null;
      obj.addEventListener(t, function (e) {
        var h = obj["on" + t];
        if (typeof h === "function") h.call(obj, e);
      });
    });
  }
  function arrayLike(items, extra) {
    var o = items.slice();
    o.item = function (i) { return this[i] || null; };
    if (extra) Object.keys(extra).forEach(function (k) { o[k] = extra[k]; });
    return o;
  }
  function pageLang() {
    return document.documentElement.lang || navigator.language || "ko-KR";
  }

  // ---------- 듣기 ----------
  class SpeechRecognitionShim extends EventTarget {
    constructor() {
      super();
      this.lang = "";
      this.continuous = false;
      this.interimResults = false;
      this.maxAlternatives = 1;
      this.grammars = null;
      handlers(this, ["start", "audiostart", "soundstart", "speechstart", "speechend", "soundend", "audioend",
        "result", "nomatch", "error", "end"]);
      this._on = false;
    }
    start() {
      if (this._on) throw new DOMException("recognition has already started", "InvalidStateError");
      if (active && active !== this) active.abort();
      active = this;
      this._on = true;
      this._started = false;
      this._stopping = false;
      this._results = [];
      this._interim = false; // 마지막 자리가 아직 중간 결과인지
      this._quiet = 0;       // continuous 에서 말이 없어 다시 들은 횟수
      this._error = null;
      B.listen(token, this.lang || pageLang(), !!this.interimResults);
    }
    stop() {
      if (!this._on) return;
      this._stopping = true;
      B.stop(token);
    }
    abort() {
      if (!this._on) return;
      this._stopping = true;
      B.abort(token);
    }
  }

  function onRecognition(ev) {
    var r = active;
    if (!r) return;
    switch (ev.type) {
      case "start":
        if (!r._started) { r._started = true; fire(r, "start"); fire(r, "audiostart"); }
        break;
      case "speechstart":
        fire(r, "soundstart"); fire(r, "speechstart");
        break;
      case "speechend":
        fire(r, "speechend"); fire(r, "soundend");
        break;
      case "result": {
        var n = Math.max(1, r.maxAlternatives | 0);
        var alts = (ev.alts || []).filter(function (a) { return a && a.t; }).slice(0, n).map(function (a) {
          return { transcript: String(a.t), confidence: typeof a.c === "number" ? a.c : 0.9 };
        });
        if (!alts.length) {
          if (ev.final && r._interim) { r._results.pop(); r._interim = false; }
          if (ev.final) fire(r, "nomatch", { results: arrayLike(r._results), resultIndex: r._results.length });
          break;
        }
        if (!ev.final && !r.interimResults) break;
        var res = arrayLike(alts, { isFinal: !!ev.final });
        var idx;
        if (r._interim) { idx = r._results.length - 1; r._results[idx] = res; } else { idx = r._results.length; r._results.push(res); }
        r._interim = !ev.final;
        if (ev.final) r._quiet = 0;
        fire(r, "result", { results: arrayLike(r._results), resultIndex: idx });
        break;
      }
      case "error":
        r._error = ev.error || "aborted";
        // continuous 에서 잠깐 말이 없는 것은 오류로 알리지 않고 다시 듣는다 (안드로이드 음성 인식은 한 문장씩)
        if (r.continuous && !r._stopping && r._error === "no-speech" && r._quiet < 3) break;
        fire(r, "error", { error: r._error, message: ev.message || "" });
        break;
      case "end":
        if (r.continuous && !r._stopping && (!r._error || (r._error === "no-speech" && r._quiet < 3))) {
          if (r._error === "no-speech") r._quiet++;
          r._error = null;
          r._interim = false;
          B.listen(token, r.lang || pageLang(), !!r.interimResults);
          return;
        }
        r._on = false;
        if (active === r) active = null;
        if (r._started) fire(r, "audioend");
        fire(r, "end");
        break;
    }
  }

  function GrammarList() { this.length = 0; }
  GrammarList.prototype.addFromString = function () { this.length++; };
  GrammarList.prototype.addFromURI = function () { this.length++; };
  GrammarList.prototype.item = function () { return null; };

  function define(name, value) {
    try { Object.defineProperty(window, name, { value: value, configurable: true, writable: true }); } catch (e) { /* 막힘 */ }
  }
  define("SpeechRecognition", SpeechRecognitionShim);
  define("webkitSpeechRecognition", SpeechRecognitionShim);
  if (typeof window.SpeechGrammarList !== "function") define("SpeechGrammarList", GrammarList);
  if (typeof window.webkitSpeechGrammarList !== "function") define("webkitSpeechGrammarList", window.SpeechGrammarList);

  // ---------- 읽어 주기 ----------
  var Utter = window.SpeechSynthesisUtterance;
  if (typeof Utter !== "function") {
    Utter = class SpeechSynthesisUtteranceShim extends EventTarget {
      constructor(text) {
        super();
        this.text = text == null ? "" : String(text);
        this.lang = "";
        this.voice = null;
        this.volume = 1;
        this.rate = 1;
        this.pitch = 1;
        handlers(this, ["start", "end", "error", "pause", "resume", "mark", "boundary"]);
      }
    };
    define("SpeechSynthesisUtterance", Utter);
  }
  var VOICE = { name: "Android", lang: navigator.language || "ko-KR", localService: true, default: true, voiceURI: "android-tts" };
  var synth = new EventTarget();
  handlers(synth, ["voiceschanged"]);
  synth.speaking = false;
  synth.pending = false;
  synth.paused = false;
  synth.speak = function (u) {
    if (!u) return;
    var id = "u" + (++seq);
    utter[id] = u;
    queue++;
    synth.pending = queue > 1;
    var lang = u.lang || (u.voice && u.voice.lang) || pageLang();
    B.speak(token, id, String(u.text == null ? "" : u.text), String(lang), Number(u.rate) || 1, Number(u.pitch) || 1);
  };
  synth.cancel = function () { B.cancelSpeak(token); };
  synth.pause = function () { synth.paused = true; };
  synth.resume = function () { synth.paused = false; };
  synth.getVoices = function () { return [VOICE]; };
  define("speechSynthesis", synth);

  function onSynthesis(ev) {
    var u = utter[ev.id];
    if (!u) return;
    var base = { utterance: u, charIndex: 0, charLength: 0, elapsedTime: 0, name: "" };
    if (ev.type === "tts-start") {
      synth.speaking = true;
      fire(u, "start", base);
      return;
    }
    delete utter[ev.id];
    queue = Math.max(0, queue - 1);
    synth.pending = queue > 1;
    if (!queue) synth.speaking = false;
    if (ev.type === "tts-error") fire(u, "error", Object.assign({ error: ev.error || "synthesis-failed" }, base));
    else fire(u, "end", base);
  }

  // 앱이 부른다 (안드로이드 쪽 결과)
  window.__urlappSpeechEvent = function (ev) {
    if (!ev || typeof ev.type !== "string") return;
    if (ev.type.indexOf("tts-") === 0) onSynthesis(ev);
    else onRecognition(ev);
  };
})("__URLAPP_TOKEN__");
