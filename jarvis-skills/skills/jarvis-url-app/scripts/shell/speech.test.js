// speech.js(음성 도우미)와 MainActivity 의 urlappAsk 호출 시험 (개발용, 크로미움/Playwright).
// 안드로이드 쪽(UrlAppSpeech)은 가짜로 두고, 앱이 보내는 결과(__urlappSpeechEvent)를 흉내 낸다.
//   node speech.test.js
"use strict";
const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");

const SHIM = fs.readFileSync(path.join(__dirname, "speech.js"), "utf8");
const MAIN = fs.readFileSync(path.join(__dirname, "MainActivity.java"), "utf8");
const TOKEN = "t0k3n";

// MainActivity.askSite 의 자바 문자열 조각을 이어 붙여 같은 스크립트를 만든다
function askScript(question, id) {
  const seg = /void askSite\([\s\S]*?String js = ([\s\S]*?)\+ JSONObject\.quote\(question\)/.exec(MAIN)[1];
  const lits = [...seg.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((m) => m[1].replace(/\\"/g, '"').replace(/\\\\/g, "\\"));
  return lits.join("") + JSON.stringify(question) + "," + JSON.stringify(id) + "," + JSON.stringify(TOKEN) + ")";
}

const results = [];
function check(label, cond, detail) {
  results.push(!!cond);
  console.log(`  [${cond ? "통과" : "실패"}] ${label}${!cond && detail ? " — " + JSON.stringify(detail) : ""}`);
}

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.setContent("<!doctype html><html lang='ko-KR'><body>jarvis</body></html>");
  await page.evaluate((tok) => {
    window.__calls = [];
    const rec = (name) => function () {
      const args = [...arguments];
      window.__calls.push({ name, args: args.slice(1), tokenOk: args[0] === tok });
    };
    window.UrlAppSpeech = { listen: rec("listen"), stop: rec("stop"), abort: rec("abort"), speak: rec("speak"),
      cancelSpeak: rec("cancelSpeak"), answer: rec("answer") };
  }, TOKEN);
  await page.evaluate(SHIM.replace('"__URLAPP_TOKEN__"', JSON.stringify(TOKEN)));
  await page.evaluate(SHIM.replace('"__URLAPP_TOKEN__"', JSON.stringify("other"))); // 두 번 넣어도 한 번만
  console.log("speech 시험 (크로미움)");

  const kinds = await page.evaluate(() => [typeof window.SpeechRecognition, typeof window.webkitSpeechRecognition,
    window.SpeechRecognition === window.webkitSpeechRecognition, typeof window.speechSynthesis.speak,
    typeof window.webkitSpeechGrammarList]);
  check("웹 음성 기능이 도우미로 바뀜", JSON.stringify(kinds) === JSON.stringify(["function", "function", true, "function", "function"]), kinds);

  // 한 번 듣기: 중간 결과 → 최종 결과 → end
  const one = await page.evaluate(() => {
    window.__calls.length = 0;
    const log = [];
    const r = new webkitSpeechRecognition();
    r.interimResults = true;
    r.onstart = () => log.push("start");
    r.onresult = (e) => log.push(["result", e.resultIndex, e.results.length, e.results[e.resultIndex][0].transcript,
      e.results[e.resultIndex].isFinal]);
    r.addEventListener("result", () => log.push("listener"));
    r.onend = () => log.push("end");
    r.start();
    let threw = "";
    try { r.start(); } catch (e) { threw = e.name; }
    const ev = window.__urlappSpeechEvent;
    ev({ type: "start" });
    ev({ type: "result", final: false, alts: [{ t: "오늘" }] });
    ev({ type: "result", final: true, alts: [{ t: "오늘 일정 알려줘", c: 0.8 }, { t: "오늘 일정" }] });
    ev({ type: "end" });
    return { log, calls: window.__calls, threw };
  });
  check("듣기: 토큰과 페이지 언어로 폰에 요청, 중간 결과 요청", one.calls.length === 1 && one.calls[0].name === "listen"
    && one.calls[0].tokenOk && one.calls[0].args[0] === "ko-KR" && one.calls[0].args[1] === true, one.calls);
  check("듣기: 이미 듣는 중에 start 는 InvalidStateError", one.threw === "InvalidStateError", one.threw);
  check("듣기: start → 중간 결과 → 같은 자리에 최종 결과 → end", JSON.stringify(one.log) === JSON.stringify([
    "start", ["result", 0, 1, "오늘", false], "listener", ["result", 0, 1, "오늘 일정 알려줘", true], "listener", "end"]), one.log);

  // 말이 없으면 no-speech 오류 → end
  const quiet = await page.evaluate(() => {
    const log = [];
    const r = new SpeechRecognition();
    r.onerror = (e) => log.push("error:" + e.error);
    r.onend = () => log.push("end");
    r.start();
    window.__urlappSpeechEvent({ type: "start" });
    window.__urlappSpeechEvent({ type: "error", error: "no-speech" });
    window.__urlappSpeechEvent({ type: "end" });
    return log;
  });
  check("듣기: 말이 없으면 error(no-speech) → end", JSON.stringify(quiet) === JSON.stringify(["error:no-speech", "end"]), quiet);

  // continuous: 한 문장 끝나도 다시 듣고, stop() 하면 끝
  const cont = await page.evaluate(() => {
    window.__calls.length = 0;
    const log = [];
    const r = new SpeechRecognition();
    r.continuous = true;
    r.onresult = (e) => log.push(e.results.length + ":" + e.results[e.resultIndex][0].transcript);
    r.onend = () => log.push("end");
    r.start();
    const ev = window.__urlappSpeechEvent;
    ev({ type: "start" });
    ev({ type: "result", final: true, alts: [{ t: "하나" }] });
    ev({ type: "end" });
    ev({ type: "start" });
    ev({ type: "error", error: "no-speech" }); // 잠깐 조용함: 오류로 알리지 않고 다시 듣기
    ev({ type: "end" });
    ev({ type: "result", final: true, alts: [{ t: "둘" }] });
    r.stop();
    ev({ type: "end" });
    return { log, calls: window.__calls.map((c) => c.name) };
  });
  check("continuous: 문장마다 다시 듣고 결과가 쌓임, stop 하면 end", JSON.stringify(cont.log) === JSON.stringify(["1:하나", "2:둘", "end"])
    && JSON.stringify(cont.calls) === JSON.stringify(["listen", "listen", "listen", "stop"]), cont);

  // 읽어 주기
  const tts = await page.evaluate(() => {
    window.__calls.length = 0;
    const log = [];
    const u = new SpeechSynthesisUtterance("회의는 두 시예요");
    u.lang = "ko-KR";
    u.rate = 1.2;
    u.onstart = () => log.push("start:" + speechSynthesis.speaking);
    u.onend = (e) => log.push("end:" + speechSynthesis.speaking + ":" + (e.utterance === u));
    speechSynthesis.speak(u);
    const c = window.__calls[0];
    window.__urlappSpeechEvent({ type: "tts-start", id: c.args[0] });
    window.__urlappSpeechEvent({ type: "tts-end", id: c.args[0] });
    const u2 = new SpeechSynthesisUtterance("긴 답");
    u2.onerror = (e) => log.push("error:" + e.error);
    speechSynthesis.speak(u2);
    speechSynthesis.cancel();
    window.__urlappSpeechEvent({ type: "tts-error", id: window.__calls[1].args[0], error: "interrupted" });
    return { log, calls: window.__calls.map((x) => [x.name, x.tokenOk].concat(x.args.slice(1)
      .map((v) => (typeof v === "number" ? Math.round(v * 100) / 100 : v)))), voices: speechSynthesis.getVoices().length };
  });
  check("읽어 주기: 글·언어·속도를 폰에 넘김", JSON.stringify(tts.calls[0]) === JSON.stringify(["speak", true, "회의는 두 시예요", "ko-KR", 1.2, 1]), tts.calls);
  check("읽어 주기: start(말하는 중) → end(끝) 처리기", JSON.stringify(tts.log.slice(0, 2)) === JSON.stringify(["start:true", "end:false:true"]), tts.log);
  check("읽어 주기: cancel 은 폰에 멈춤 요청, 읽던 것은 error(interrupted)", tts.calls[2] && tts.calls[2][0] === "cancelSpeak"
    && tts.log[2] === "error:interrupted" && tts.voices === 1, tts);

  // MainActivity.askSite: 사이트의 urlappAsk 를 불러 답을 통로로 돌려준다
  const asks = await page.evaluate(async (scripts) => {
    window.__calls.length = 0;
    let n = 0;
    window.urlappAsk = async (q) => {
      n++;
      if (q === "글") return "오늘 일정은 두 개예요";
      if (q === "객체") return { text: "할 일 3개" };
      throw new Error("x");
    };
    for (const s of scripts) eval(s);
    await new Promise((r) => setTimeout(r, 50));
    return { n, calls: window.__calls.map((c) => [c.name, c.tokenOk].concat(c.args)) };
  }, [askScript("글", "q1"), askScript("객체", "q2"), askScript("오류", "q3")]);
  check("urlappAsk: 글·{text} 답은 그대로, 오류면 ok=false", asks.n === 3 && JSON.stringify(asks.calls) === JSON.stringify([
    ["answer", true, "q1", "오늘 일정은 두 개예요", true], ["answer", true, "q2", "할 일 3개", true], ["answer", true, "q3", "", false]]), asks);

  await browser.close();
  const ok = results.filter(Boolean).length;
  console.log(`speech 시험: ${ok}개 통과, ${results.length - ok}개 실패`);
  process.exit(results.length === ok ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
