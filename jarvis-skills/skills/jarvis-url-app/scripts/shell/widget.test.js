// widget-runner.js + widgets/smart-ledger.js 시험 (개발용, 크로미움/Playwright).
// 가계부 사이트의 /api/summary 를 흉내 내는 시험 서버(지어낸 숫자)와 가짜 UrlAppBridge 로 확인한다.
//   node widget.test.js
"use strict";
const http = require("http");
const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");

const RUNNER = fs.readFileSync(path.join(__dirname, "widget-runner.js"), "utf8");
const LEDGER = fs.readFileSync(path.join(__dirname, "..", "..", "widgets", "smart-ledger.js"), "utf8");
const AJ = fs.readFileSync(path.join(__dirname, "..", "..", "widgets", "ajgahub-board.js"), "utf8");
const CHARLES = fs.readFileSync(path.join(__dirname, "..", "..", "widgets", "charles-jobs.js"), "utf8");
const SITEP = fs.readFileSync(path.join(__dirname, "..", "..", "widgets", "site-provided.js"), "utf8");
const GAOPS = fs.readFileSync(path.join(__dirname, "..", "..", "widgets", "gaops-tasks.js"), "utf8")
  .replace(/__PARAM_GAOPS_PROJECT__/g, "fake-project").replace(/__PARAM_GAOPS_API_KEY__/g, "FAKE_WEB_KEY");
const FAKE_REF = "abcdefghijklmnopqrst"; // 지어낸 Supabase 프로젝트·키·토큰
const FAKE_KEY = "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoiYW5vbiJ9.fakefakefake";
const SLOT = "(/*__WIDGET_FN__*/ null)";
const ROWS_SLOT = "/*__WIDGET_ROWS__*/4";
const ALT_ROWS_SLOT = "/*__WIDGET_ALT_ROWS__*/0";
// url_app.py 의 widget_asset 과 같게: 스크립트를 자리에 넣고, "// urlapp:rows N" 이 있으면 줄 수를 바꾼다
function asset(script) {
  const m = /^\s*\/\/\s*urlapp:rows\s+(\d+)\s*$/m.exec(script);
  const a = /^\s*\/\/\s*urlapp:alt-rows\s+(\d+)\s*$/m.exec(script);
  const rows = m ? m[1] : "4";
  const scroll = /^\s*\/\/\s*urlapp:alt-scroll\s*$/m.test(script);
  return RUNNER.replace(ROWS_SLOT, rows).replace(ALT_ROWS_SLOT, scroll ? "30" : a ? a[1] : rows)
    .replace(SLOT, "(\n" + script.trim().replace(/;\s*$/, "") + "\n)");
}
const ASSET = asset(LEDGER);
const TOKEN = "t0k3n";

const results = [];
function check(label, cond, detail) {
  results.push(!!cond);
  console.log(`  [${cond ? "통과" : "실패"}] ${label}${!cond && detail ? " — " + JSON.stringify(detail) : ""}`);
}

(async () => {
  let summary = { income: 5000000, fixed: 1200000, variable: 800000, savings: 1000000, investments: 0 };
  const requests = [];
  const detailReqs = [];
  // 상세 변동지출 (지어낸 값): 메모에 "카드"가 있는 것만, 창수·지현만 더한다. 같은 줄(id·표)이 두 번 와도 한 번만.
  const details = [
    { id: 1, source_table: "monthly_details", date: "2026-11-03", details: "메가커피", amount: 12500, earner_name: "창수", memo: "현대카드" },
    { id: 2, source_table: "monthly_details", date: "2026-11-04", details: "시장", amount: 5000, earner_name: "창수", memo: "현금" },
    { id: 3, source_table: "monthly_details", date: "2026-11-05", details: "이마트", amount: 30000, earner_name: "지현", memo: "삼성카드" },
    { id: 4, source_table: "monthly_details", date: "2026-11-06", details: "약국", amount: 7000, earner_name: "지현", memo: "" },
    { id: 5, source_table: "monthly_details", date: "2026-11-07", details: "문구점", amount: 9999, earner_name: "태하", memo: "신한카드" },
    { id: 6, source_table: "monthly_details", date: "2026-11-08", details: "GS25", amount: "1,000", earner_name: "창수 ", memo: "현대카드 M" },
    { id: 1, source_table: "monthly_details", date: "2026-11-03", details: "메가커피", amount: 12500, earner_name: "창수", memo: "현대카드" },
  ];
  let cjMode = "off"; // CHARLES 위젯: off | jobs | idle | login
  let cjTodo = "none"; // 두 번째 위젯(할 일): none | site(/urlapp-todo.json) | memos(/api/memos) | todos(/api/todos) | many
  const cjReqs = [];
  let spMode = "none"; // 범용 위젯: /urlapp-widget.json 이 어떻게 답하는지 (none = 일반 페이지)
  let serverSession = false; // 서버(/api/session)가 로그인이라고 하는지 (보이지 않는 웹 화면에서 새로 고칠 때)
  const server = http.createServer((req, res) => {
    if ((req.url === "/api/dev/requests" || req.url === "/api/reels/publish_info") && cjMode !== "off") {
      cjReqs.push(req.method + " " + req.url);
      if (cjMode === "login") { res.writeHead(401, { "Content-Type": "application/json" }); return res.end("{}"); }
      const iso = (minAgo) => { const d = new Date(Date.now() - minAgo * 60000); const z = (n) => String(n).padStart(2, "0");
        return `${d.getFullYear()}-${z(d.getMonth() + 1)}-${z(d.getDate())}T${z(d.getHours())}:${z(d.getMinutes())}:${z(d.getSeconds())}`; };
      res.writeHead(200, { "Content-Type": "application/json" });
      if (req.url === "/api/reels/publish_info") {
        return res.end(JSON.stringify({ info: cjMode === "jobs" ? { title: "스탠리 퀜처 H2.0 텀블러", batch_id: "jarvis_kv_20261120_143902" } : null }));
      }
      const items = cjMode === "idle" ? [] : cjMode === "music"
        ? [{ id: 30, kind: "music", text: "[음악 제작 요청] …", status: "claimed", claimed_at: iso(3) },
           { id: 28, kind: "music", text: "[음악 제작 요청] …", status: "done", finished_at: iso(90) }]
        : [{ id: 31, kind: "dev", text: "/개발 위젯 고쳐줘\n자세히", status: "queued", created: iso(1) },
           { id: 30, kind: "music", text: "[음악 제작 요청] …", status: "claimed", claimed_at: iso(3) },
           { id: 29, kind: "byeongmat", text: "[병맛노래 제작 요청] …", status: "done", finished_at: iso(30) }];
      return res.end(JSON.stringify({ items, watcher: {}, counts: {} }));
    }
    if (req.url.startsWith("/api/job") && cjMode !== "off") {
      cjReqs.push(req.method + " " + req.url);
      if (cjMode === "login") { res.writeHead(401, { "Content-Type": "application/json" }); return res.end("{}"); }
      const now = Math.floor(Date.now() / 1000);
      res.writeHead(200, { "Content-Type": "application/json" });
      if (req.url === "/api/jobs/active") {
        return res.end(JSON.stringify(cjMode === "idle" || cjMode === "music" ? { running: [], unseen: [] } : {
          running: [
            { id: "aaaaaaaaaaaa", kind: "agent", title: "릴스 만들기", message: "", status: "running", waiting: false, started: now - 300, next: 50 },
            { id: "bbbbbbbbbbbb", kind: "agent", title: "", message: "메일 정리해줘\n자세히", status: "running", waiting: true, started: now - 60, next: 5 },
            { id: "eeeeeeeeeeee", kind: "chat", title: "자료 조사", message: "", status: "running", waiting: false, started: now - 720, next: 3 },
          ],
          unseen: [
            { id: "cccccccccccc", kind: "chat", title: "블로그 글", status: "done" },
            { id: "dddddddddddd", kind: "agent", message: "유튜브 업로드", status: "error" },
          ] }));
      }
      if (req.url.startsWith("/api/job/aaaaaaaaaaaa?")) {
        return res.end(JSON.stringify({ events: [{ type: "text", seq: 1 }, { type: "progress", percent: 62, label: "장면 3/8", seq: 2 }], next: 3 }));
      }
      return res.end(JSON.stringify({ events: [{ type: "text", seq: 1 }], next: 2 }));
    }
    if (req.url === "/api/tasks" && spMode === "tasks") {
      res.writeHead(200, { "Content-Type": "application/json" });
      return res.end(JSON.stringify({ ok: true, data: { tasks: [
        { id: 1, title: "영상 편집", status: "done", progress: 1 },
        { id: 2, title: "블로그 글 작성", status: "in_progress", progress: 0.6 },
        { id: 3, name: "메일 정리", status: "failed" },
        { id: 4, title: "자료 조사", state: "running", percent: 35 },
      ] } }));
    }
    if (req.url === "/jarvis-bars") {
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      return res.end('<!doctype html><html><head><title>CHARLES</title></head><body><div id="app"></div><script>'
        + 'setTimeout(function(){document.getElementById("app").innerHTML='
        + '\'<div class="card"><h3>보고서 정리</h3><progress value="3" max="4"></progress></div>'
        + '<div class="card"><span>코드 리뷰</span><div role="progressbar" aria-valuenow="40"></div><span>40%</span></div>\';},800);'
        + '</script></body></html>');
    }
    if (req.url === "/urlapp-todo.json" && cjTodo === "site") {
      res.writeHead(200, { "Content-Type": "application/json" });
      return res.end(JSON.stringify({ title: "할 일 3", note: "오늘 1",
        rows: [{ label: "전기차 보고", value: "D-2", tone: "pos" }, { label: "견적 회신", value: "오늘" }, { label: "명함 정리", value: "" }] }));
    }
    if (req.url === "/api/todos" && (cjTodo === "todos" || cjTodo === "many")) {
      const d = (n) => { const x = new Date(); x.setDate(x.getDate() + n);
        return `${x.getFullYear()}-${String(x.getMonth() + 1).padStart(2, "0")}-${String(x.getDate()).padStart(2, "0")}`; };
      const list = cjTodo === "many"
        ? Array.from({ length: 40 }, (_, i) => ({ id: i, title: `할 일 ${i + 1}`, due: d(i), completed: false }))
        : [{ id: 1, title: "테스트", due: d(0), source: "naver", completed: false },
          { id: 2, title: "엄마 점심", due: d(1), source: "naver", completed: false },
          { id: 3, title: "끝난 것", due: d(0), completed: true }];
      res.writeHead(200, { "Content-Type": "application/json" });
      return res.end(JSON.stringify({ todos: list }));
    }
    if (req.url.startsWith("/api/memos") && cjTodo === "memos") {
      const d = (n) => { const x = new Date(); x.setDate(x.getDate() + n);
        return `${x.getFullYear()}-${String(x.getMonth() + 1).padStart(2, "0")}-${String(x.getDate()).padStart(2, "0")}`; };
      res.writeHead(200, { "Content-Type": "application/json" });
      return res.end(JSON.stringify({ report: "…", items: [
        { id: "a1", category: "개인·기타", text: "세탁소   찾기", due: "", done: false },
        { id: "a2", category: "업무", text: "전기차 보고", due: d(3), done: false },
        { id: "a3", category: "일정", text: "검진 예약", due: d(-1), done: false },
        { id: "a4", category: "업무", text: "끝난 일", due: d(0), done: true },
        { id: "a5", category: "비용·영수증", text: "택시비 정산", due: d(0), done: false },
        { id: "a6", category: "업무", text: "", due: d(1), done: false }] }));
    }
    if ((req.url === "/urlapp-widget.json" || req.url === "/urlapp-notify.json") && cjMode === "siteIdle") {
      res.writeHead(200, { "Content-Type": "application/json" });
      return res.end(JSON.stringify(req.url === "/urlapp-widget.json"
        ? { title: "자비스 · 쉬는 중", rows: [], note: "16:40 기준" } : { items: [] }));
    }
    if ((req.url === "/urlapp-widget.json" || req.url === "/urlapp-notify.json") && cjMode === "site") {
      cjReqs.push(req.method + " " + req.url);
      res.writeHead(200, { "Content-Type": "application/json" });
      return res.end(JSON.stringify(req.url === "/urlapp-widget.json"
        ? { title: "자비스 · 진행 중 1", rows: [{ label: "음악 요청 #30 · 발행 묶음", value: "83%", tone: "pos" }], note: "작업창과 같은 값" }
        : { items: [{ id: "n1", title: "작업 끝", body: "x", open: "/" }] }));
    }
    if (req.url === "/urlapp-widget.json" && spMode !== "none" && spMode !== "tasks") {
      if (spMode === "401") { res.writeHead(401, { "Content-Type": "application/json" }); return res.end("{}"); }
      res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
      return res.end(JSON.stringify(spMode === "empty" ? { title: "x", rows: [] } : { title: "오늘의 자비스", note: "할 일 3",
        rows: [{ label: "일정", value: "14:00 회의", tone: "pos" }, { label: "서버", value: "정상" }] }));
    }
    if (req.url === "/api/session") {
      res.writeHead(200, { "Content-Type": "application/json" });
      return res.end(JSON.stringify({ authenticated: serverSession, needsPasswordSetup: false }));
    }
    if (req.url === "/urlapp.json") {
      res.writeHead(200, { "Content-Type": "application/json" });
      return res.end(JSON.stringify({ version: 1, cards: { history: { path: "/api/monthly-details?month={month}",
        fields: { details: "details", amount: "amount", owner: "earner_name", memo: "memo", id: "id", table: "source_table" } } } }));
    }
    if (req.url.startsWith("/api/monthly-details?month=")) {
      detailReqs.push(req.url);
      res.writeHead(200, { "Content-Type": "application/json" });
      return res.end(JSON.stringify(details));
    }
    if (req.url.startsWith("/api/summary")) {
      requests.push(req.url);
      res.writeHead(200, { "Content-Type": "application/json" });
      return res.end(JSON.stringify(summary));
    }
    if (req.url.startsWith("/api/monthly-details/add-single") && req.method === "POST") {
      summary = { ...summary, variable: summary.variable + 6560 }; // 메가커피 한 잔 지출 추가
      res.writeHead(200, { "Content-Type": "application/json" });
      return res.end("{}");
    }
    if (req.url === "/aj/index.js") {
      res.writeHead(200, { "Content-Type": "text/javascript" });
      return res.end(`const z9="https://${FAKE_REF}.supabase.co",k="${FAKE_KEY}";window.__siteLoaded=1;`);
    }
    if (req.url === "/aj/") {
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      return res.end('<!doctype html><html><head><script type="module" src="/aj/index.js"></script></head><body>AJ</body></html>');
    }
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end("<!doctype html><html><body>가계부</body></html>");
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${server.address().port}/`;
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto(base);
  await page.evaluate((tok) => {
    window.__w = [];
    window.UrlAppBridge = { widget: (t, json) => { if (t === tok) window.__w.push(JSON.parse(json)); } };
  }, TOKEN);

  console.log("widget 시험 (크로미움)");
  await page.evaluate(ASSET.replace('"__URLAPP_TOKEN__"', JSON.stringify(TOKEN)));
  await page.waitForTimeout(500);
  check("로그인 전에는 읽지 않음", (await page.evaluate(() => __w.length)) === 0 && requests.length === 0
    && detailReqs.length === 0, [requests, detailReqs]);

  await page.evaluate(() => { sessionStorage.setItem("isAuthenticated", "true"); window.__urlappWidgetRun(); });
  await page.waitForFunction(() => __w.length === 1, null, { timeout: 5000 });
  const w = await page.evaluate(() => __w[0]);
  const now = new Date();
  const m = now.getMonth() + 1;
  check("제목", w.title === `${m}월 우리집 가계부`, w.title);
  check("수입·지출(고정+변동)·저축·차액 + 창수·지현 카드", JSON.stringify(w.rows.map((r) => [r.label, r.value, r.tone]))
    === JSON.stringify([
      ["수입", "5,000,000원", "pos"], ["지출", "2,000,000원", "neg"], ["저축", "1,000,000원", ""],
      ["수입 − 지출", "3,000,000원", "pos"], ["창수 카드", "13,500원", ""], ["지현 카드", "30,000원", ""]]), w.rows);
  const last = new Date(now.getFullYear(), m, 0).getDate();
  const ym = `${now.getFullYear()}-${String(m).padStart(2, "0")}`;
  check("대시보드와 같은 요청 (이번 달, 전체 사용자)",
    requests[0] === `/api/summary?startDate=${ym}-01&endDate=${ym}-${String(last).padStart(2, "0")}&earnerId=all`, requests);
  check("카드 합계는 이번 달 상세 내역 (연동 파일의 주소)", detailReqs[0] === `/api/monthly-details?month=${ym}`, detailReqs);
  const alt = w.alt || {};
  check("두 번째 위젯(카드 사용): 제목에 합계", alt.title === `${m}월 카드 사용 43,500원`, alt.title);
  check("두 번째 위젯(3×2, 4줄): 사람별 합계 + 최근 카드 내역 2건 (최신순, 현금·다른 사람 제외)",
    JSON.stringify((alt.rows || []).map((r) => [r.label, r.value])) === JSON.stringify([
      ["창수 합계", "13,500원"], ["지현 합계", "30,000원"], ["11/8 창수 · GS25", "1,000원"],
      ["11/5 지현 · 이마트", "30,000원"]]), alt.rows);
  check("두 번째 위젯: 아래 줄에 건수", alt.note === "이번 달 카드 3건", alt.note);

  // 사이트가 지출을 저장하면(POST) 잠시 뒤 위젯이 다시 읽는다
  await page.evaluate(() => fetch("/api/monthly-details/add-single", { method: "POST", body: "{}" }));
  await page.waitForFunction(() => __w.length >= 2, null, { timeout: 5000 });
  const w2 = await page.evaluate(() => __w[__w.length - 1]);
  check("저장 직후 다시 읽어 지출이 바뀜", w2.rows[1].value === "2,006,560원", w2.rows);
  // GET 은 다시 읽기를 부르지 않는다
  const before = await page.evaluate(() => __w.length);
  await page.evaluate(() => Promise.all([fetch("/api/summary?x=1"), fetch("/api/summary?x=2", { method: "HEAD" })]));
  await page.waitForTimeout(2200);
  check("GET·HEAD 요청은 다시 읽기를 부르지 않음", (await page.evaluate(() => __w.length)) === before);

  // 두 번 넣어도 한 번만
  await page.evaluate(ASSET.replace('"__URLAPP_TOKEN__"', JSON.stringify(TOKEN)));
  await page.waitForTimeout(400);
  check("두 번 넣어도 실행기는 하나", (await page.evaluate(() => __w.length)) === before);

  // 사이트가 직접 위젯 함수를 주면 그것을 먼저 쓴다 (사이트를 고쳐도 앱 업데이트 없이)
  await page.evaluate(() => { window.urlappWidget = async () => ({ title: "사이트가 만든 위젯", rows: [{ label: "a", value: "1" }] }); });
  const n0 = await page.evaluate(() => __w.length);
  await page.evaluate(() => window.__urlappWidgetRun());
  await page.waitForFunction((n) => __w.length > n, n0, { timeout: 5000 });
  const ws = await page.evaluate(() => __w[__w.length - 1]);
  check("사이트의 window.urlappWidget 요약을 쓰고 카드 줄을 덧붙임", ws.title === "사이트가 만든 위젯"
    && JSON.stringify(ws.rows.map((r) => [r.label, r.value])) === JSON.stringify([["a", "1"], ["창수 카드", "13,500원"],
      ["지현 카드", "30,000원"]]), ws);
  // 사이트 요약이 실패하면 앱의 계산으로
  await page.evaluate(() => { window.urlappWidget = async () => { throw new Error("x"); }; });
  const n1 = await page.evaluate(() => __w.length);
  await page.evaluate(() => window.__urlappWidgetRun());
  await page.waitForFunction((n) => __w.length > n, n1, { timeout: 5000 });
  const wf = await page.evaluate(() => __w[__w.length - 1]);
  check("사이트 요약이 실패하면 앱이 계산 (6줄)", wf.title.endsWith("우리집 가계부") && wf.rows.length === 6, wf);
  // 카드 내역을 못 읽으면: 첫 위젯은 4줄, 두 번째 위젯은 이유
  await page.route("**/api/monthly-details?month=*", (route) => route.fulfill({ status: 401, body: "{}" }));
  const n2 = await page.evaluate(() => __w.length);
  await page.evaluate(() => window.__urlappWidgetRun());
  await page.waitForFunction((n) => __w.length > n, n2, { timeout: 5000 });
  const wu = await page.evaluate(() => __w[__w.length - 1]);
  check("카드 내역을 못 읽으면 4줄 + 두 번째 위젯에 이유", wu.rows.length === 4 && wu.alt && wu.alt.rows.length === 0
    && wu.alt.note === "카드 내역을 읽지 못했어요", wu);
  await page.unroute("**/api/monthly-details?month=*");
  await page.evaluate(() => { delete window.urlappWidget; });

  // 값이 크거나 줄이 많아도 잘라서 넘긴다
  const page2 = await browser.newPage();
  await page2.goto(base);
  await page2.evaluate((tok) => {
    window.__w = [];
    window.UrlAppBridge = { widget: (t, j) => { if (t === tok) window.__w.push(JSON.parse(j)); } };
  }, TOKEN);
  const custom = RUNNER.replace(SLOT,
    "(async function(){return {title:'x'.repeat(99), rows:[1,2,3,4,5,6].map(i=>({label:'l'+i,value:'v'.repeat(50)}))};})");
  await page2.evaluate(custom.replace('"__URLAPP_TOKEN__"', JSON.stringify(TOKEN)));
  await page2.waitForFunction(() => __w.length === 1, null, { timeout: 5000 });
  const clipped = await page2.evaluate(() => __w[0]);
  check("제목 30자·4줄·값 24자로 자름", clipped && clipped.title && clipped.title.length === 30 && clipped.rows.length === 4
    && clipped.rows[0].value.length === 24, clipped);

  // "// urlapp:rows 6" 이면 6줄까지. 인자 없는 스크립트는 사이트 함수가 먼저 (예전 그대로)
  const page3 = await browser.newPage();
  await page3.goto(base);
  await page3.evaluate((tok) => {
    window.__w = [];
    window.UrlAppBridge = { widget: (t, j) => { if (t === tok) window.__w.push(JSON.parse(j)); } };
  }, TOKEN);
  await page3.evaluate(asset("// urlapp:rows 6\nasync function(){return {title:'t', rows:[1,2,3,4,5,6,7,8].map(i=>({label:'l'+i,value:'v'}))};}")
    .replace('"__URLAPP_TOKEN__"', JSON.stringify(TOKEN)));
  await page3.waitForFunction(() => __w.length === 1, null, { timeout: 5000 });
  check("urlapp:rows 6 이면 6줄까지", (await page3.evaluate(() => __w[0].rows.length)) === 6);
  const page5 = await browser.newPage();
  await page5.goto(base);
  await page5.evaluate((tok) => {
    window.__w = [];
    window.UrlAppBridge = { widget: (t, j) => { if (t === tok) window.__w.push(JSON.parse(j)); } };
  }, TOKEN);
  await page5.evaluate(asset("async function(){return {title:'a', rows:[], alt:{title:'y'.repeat(50), rows:[1,2,3,4,5,6].map(i=>({label:'l'+i,value:'v'}))}};}")
    .replace('"__URLAPP_TOKEN__"', JSON.stringify(TOKEN)));
  await page5.waitForFunction(() => __w.length === 1, null, { timeout: 5000 });
  const a5 = await page5.evaluate(() => __w[0].alt);
  check("두 번째 위젯 값도 같은 규칙으로 자름 (30자·줄 수)", a5 && a5.title.length === 30 && a5.rows.length === 4, a5);

  // 위젯 새로고침(보이지 않는 웹 화면): sessionStorage 는 비어 있지만 서버 세션(쿠키)은 살아 있을 때
  const bg = await browser.newPage();
  await bg.goto(base);
  await bg.evaluate((tok) => {
    window.__w = [];
    window.UrlAppBridge = { widget: (t, j) => { if (t === tok) window.__w.push(JSON.parse(j)); } };
  }, TOKEN);
  serverSession = false;
  await bg.evaluate(ASSET.replace('"__URLAPP_TOKEN__"', JSON.stringify(TOKEN)));
  await bg.waitForTimeout(700);
  check("새로고침: 서버도 로그인 아님이면 읽지 않음", (await bg.evaluate(() => __w.length)) === 0);
  serverSession = true;
  await bg.evaluate(() => window.__urlappWidgetRun());
  await bg.waitForFunction(() => __w.length >= 1, null, { timeout: 5000 });
  const wb = await bg.evaluate(() => __w[__w.length - 1]);
  check("새로고침: sessionStorage 가 비어도 서버 세션이 있으면 채움 (6줄 + 카드 위젯)", wb.rows.length === 6 && wb.alt
    && wb.alt.title.includes("카드 사용"), wb);
  serverSession = false;
  const page6 = await browser.newPage();
  await page6.goto(base);
  await page6.evaluate((tok) => {
    window.__w = [];
    window.UrlAppBridge = { widget: (t, j) => { if (t === tok) window.__w.push(JSON.parse(j)); } };
  }, TOKEN);
  await page6.evaluate(asset("// urlapp:rows 6\n// urlapp:alt-rows 3\nasync function(){var r=[1,2,3,4,5,6,7].map(i=>({label:'l'+i,value:'v'}));"
    + "return {title:'a', rows:r, alt:{title:'b', rows:r}};}").replace('"__URLAPP_TOKEN__"', JSON.stringify(TOKEN)));
  await page6.waitForFunction(() => __w.length === 1, null, { timeout: 5000 });
  const w6 = await page6.evaluate(() => __w[0]);
  check("첫 위젯과 두 번째 위젯의 줄 수가 따로 (6·3)", w6.rows.length === 6 && w6.alt.rows.length === 3, w6);
  const page4 = await browser.newPage();
  await page4.goto(base);
  await page4.evaluate((tok) => {
    window.__w = [];
    window.UrlAppBridge = { widget: (t, j) => { if (t === tok) window.__w.push(JSON.parse(j)); } };
    window.urlappWidget = async () => ({ title: "site", rows: [] });
  }, TOKEN);
  await page4.evaluate(asset("async function(){return {title:'app', rows:[]};}").replace('"__URLAPP_TOKEN__"', JSON.stringify(TOKEN)));
  await page4.waitForFunction(() => __w.length === 1, null, { timeout: 5000 });
  check("인자 없는 앱 스크립트는 사이트 함수가 먼저", (await page4.evaluate(() => __w[0].title)) === "site");

  // AJ GA HUB: 팀 게시판 위젯
  const aj = await browser.newPage();
  const sb = [];
  const d0 = new Date();
  const iso = (daysAgo, h) => new Date(d0.getFullYear(), d0.getMonth(), d0.getDate() - daysAgo, h, 0, 0).toISOString();
  await aj.route(`https://${FAKE_REF}.supabase.co/**`, (route) => {
    const req = route.request();
    sb.push({ url: req.url(), apikey: req.headers()["apikey"], auth: req.headers()["authorization"] });
    route.fulfill({ status: 200, contentType: "application/json", headers: { "Access-Control-Allow-Origin": "*" },
      body: JSON.stringify([
        { title: "11월 성과 공유회 결과", category: "achievement", author_name: "A", created_at: iso(0, 10) },
        { title: "연차 사용 안내", category: "vacation", author_name: "B", created_at: iso(0, 9) },
        { title: "부산 출장", category: "business_trip", author_name: "C", created_at: iso(1, 15) },
        { title: "", category: "unknown", author_name: "D", created_at: iso(5, 11) },
        { title: "다섯 번째 글", category: "story", author_name: "E", created_at: iso(6, 11) },
      ]) });
  });
  await aj.goto(base + "aj/");
  await aj.waitForFunction(() => window.__siteLoaded === 1);
  await aj.evaluate((tok) => {
    window.__w = [];
    window.UrlAppBridge = { widget: (t, j) => { if (t === tok) window.__w.push(JSON.parse(j)); } };
  }, TOKEN);
  const ajAsset = RUNNER.replace(SLOT, "(\n" + AJ.trim().replace(/;\s*$/, "") + "\n)");
  await aj.evaluate(ajAsset.replace('"__URLAPP_TOKEN__"', JSON.stringify(TOKEN)));
  await aj.waitForTimeout(600);
  check("AJ: 로그인 전에는 Supabase 에 요청하지 않음", sb.length === 0 && (await aj.evaluate(() => __w.length)) === 0, sb);
  await aj.evaluate((ref) => {
    localStorage.setItem(`sb-${ref}-auth-token`, JSON.stringify({ access_token: "user-session-jwt" }));
    window.__urlappWidgetRun();
  }, FAKE_REF);
  await aj.waitForFunction(() => __w.length === 1, null, { timeout: 5000 });
  const aw = await aj.evaluate(() => __w[0]);
  check("AJ: 사용자 세션과 사이트의 공개 키로 요청", sb.length === 1 && sb[0].apikey === FAKE_KEY
    && sb[0].auth === "Bearer user-session-jwt", sb);
  check("AJ: 최신순 20개만 필요한 열로", sb[0] && sb[0].url.endsWith(
    "/rest/v1/team_board?select=title,category,author_name,created_at&order=created_at.desc&limit=20"), sb[0] && sb[0].url);
  const o = new Date(d0.getFullYear(), d0.getMonth(), d0.getDate() - 5);
  check("AJ: 분류 · 제목, 오늘/어제/날짜", JSON.stringify(aw.rows.map((r) => [r.label, r.value, r.tone])) === JSON.stringify([
    ["성과 · 11월 성과 공유회 결과", "오늘", "pos"], ["휴가 · 연차 사용 안내", "오늘", "pos"],
    ["출장 · 부산 출장", "어제", ""], ["(제목 없음)", `${o.getMonth() + 1}/${o.getDate()}`, ""]]), aw.rows);
  check("AJ: 제목과 오늘 새 글 수", aw.title === "AJ GA HUB 팀 게시판" && aw.note === "오늘 새 글 2개", aw);

  // GA Ops: 진행 중 과업 (가짜 Firebase 로그인·Firestore)
  const ga = await browser.newPage();
  const g = { signUp: 0, refresh: 0, list: [], fail403: false };
  const ymd = (n) => { const d = new Date(d0.getFullYear(), d0.getMonth(), d0.getDate() + n);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`; };
  const doc = (title, status, due, prog, who, prio) => ({ name: "x", fields: {
    title: { stringValue: title }, status: { stringValue: status }, priority: { stringValue: prio || "보통" },
    ...(due ? { dueDate: { stringValue: due } } : {}), manualProgress: { integerValue: String(prog) },
    mainAssigneeName: { stringValue: who } } });
  const cors = { "Access-Control-Allow-Origin": "*" };
  await ga.route("https://identitytoolkit.googleapis.com/**", (route) => { g.signUp++;
    route.fulfill({ status: 200, contentType: "application/json", headers: cors,
      body: JSON.stringify({ idToken: "anon-id-1", refreshToken: "anon-refresh", expiresIn: "3600" }) }); });
  await ga.route("https://securetoken.googleapis.com/**", (route) => { g.refresh++;
    route.fulfill({ status: 200, contentType: "application/json", headers: cors,
      body: JSON.stringify({ id_token: "anon-id-2", refresh_token: "anon-refresh-2", expires_in: "3600" }) }); });
  await ga.route("https://firestore.googleapis.com/**", (route) => {
    const req = route.request();
    g.list.push({ url: req.url(), method: req.method(), auth: req.headers()["authorization"] });
    if (g.fail403) return route.fulfill({ status: 403, contentType: "application/json", headers: cors, body: "{}" });
    route.fulfill({ status: 200, contentType: "application/json", headers: cors, body: JSON.stringify({ documents: [
      doc("에너지모니터링배포", "진행 중", ymd(2), 0, "최성훈", "높음"),
      doc("사무용품 정리", "진행 중", ymd(10), 40, "김창수"),
      doc("소방 점검", "진행 중", ymd(-1), 80, "이영희"),
      doc("법인차량 보험", "진행 중", null, 10, ""),
      doc("완료된 일", "종료", ymd(1), 100, "A"),
      doc("준비 중인 일", "준비", ymd(1), 0, "B"),
      doc("다섯째 진행", "진행 중", ymd(20), 5, "C"),
    ] }) });
  });
  await ga.goto(base + "aj/");
  await ga.waitForFunction(() => window.__siteLoaded === 1);
  await ga.evaluate((tok) => {
    window.__w = []; window.__runs = 0;
    window.UrlAppBridge = { widget: (t, j) => { if (t === tok) window.__w.push(JSON.parse(j)); } };
  }, TOKEN);
  const gaAsset = RUNNER.replace(SLOT, "(\n" + GAOPS.trim().replace(/;\s*$/, "") + "\n)");
  await ga.evaluate(gaAsset.replace('"__URLAPP_TOKEN__"', JSON.stringify(TOKEN)));
  await ga.waitForTimeout(500);
  check("GA Ops: AJ GA HUB 로그인 전에는 아무 요청도 안 함", g.signUp + g.list.length === 0, g);
  // 실제 AJ GA HUB 처럼 세션을 저장하지 않고(persistSession: false) 화면에 "로그아웃" 버튼만 나타난다.
  // 수동으로 다시 돌리지 않아도 10초 재시도로 채워져야 한다.
  const loginAt = Date.now();
  await ga.evaluate(() => { const b = document.createElement("button"); b.title = "로그아웃"; document.body.appendChild(b); });
  await ga.waitForFunction(() => __w.length === 1, null, { timeout: 15000 });
  const waited = Date.now() - loginAt;
  check("GA Ops: 로그인(로그아웃 버튼)하면 저절로 채워짐 (세션 저장 없이)", waited < 12000 && g.signUp === 1, { waited, g });
  await ga.waitForTimeout(2500); // 자기 로그인(POST) 때문에 다시 돌지 않는지
  const gw = await ga.evaluate(() => ({ w: __w, n: __w.length }));
  check("GA Ops: 익명 로그인 1번, 자기 요청으로 반복하지 않음", g.signUp === 1 && gw.n === 1, { g, n: gw.n });
  check("GA Ops: 읽기는 GET, 필요한 칸만, 토큰 사용", g.list.length === 1 && g.list[0].method === "GET"
    && g.list[0].auth === "Bearer anon-id-1" && g.list[0].url.includes("/projects/fake-project/databases/(default)/documents/tasks?")
    && g.list[0].url.includes("mask.fieldPaths=manualProgress") && g.list[0].url.includes("key=FAKE_WEB_KEY"), g.list[0]);
  const w0 = gw.w[0];
  check("GA Ops: 제목에 진행 중 건수 (종료·준비 제외)", w0.title === "GA Ops 진행 중 과업 5건", w0.title);
  check("GA Ops: 급한 순, 과업명 · 담당자, D-값 · 진척율", JSON.stringify(w0.rows.map((r) => [r.label, r.value, r.tone]))
    === JSON.stringify([["소방 점검 · 이영희", "D+1 · 80%", "neg"], ["에너지모니터링배포 · 최성훈", "D-2 · 0%", "neg"],
      ["사무용품 정리 · 김창수", "D-10 · 40%", ""], ["다섯째 진행 · C", "D-20 · 5%", ""]]), w0.rows);
  check("GA Ops: 아래 줄에 경과·마감 임박 수", w0.note === "경과 1 · 마감 임박 1", w0.note);
  await ga.evaluate(() => window.__urlappWidgetRun());
  await ga.waitForFunction(() => __w.length === 2, null, { timeout: 5000 });
  check("GA Ops: 두 번째는 저장된 토큰 사용 (새 익명 사용자 안 만듦)", g.signUp === 1 && g.refresh === 0 && g.list.length === 2, g);
  await ga.evaluate(() => { const a = JSON.parse(localStorage.getItem("urlapp_gaops_auth")); a.exp = Date.now() - 1000;
    localStorage.setItem("urlapp_gaops_auth", JSON.stringify(a)); window.__urlappWidgetRun(); });
  await ga.waitForFunction(() => __w.length === 3, null, { timeout: 5000 });
  check("GA Ops: 만료되면 갱신 토큰으로 새 토큰", g.refresh === 1 && g.signUp === 1 && g.list[2].auth === "Bearer anon-id-2", g);
  g.fail403 = true;
  await ga.evaluate(() => window.__urlappWidgetRun());
  await ga.waitForFunction(() => __w.length === 4, null, { timeout: 5000 });
  const w4 = await ga.evaluate(() => ({ w: __w[3], auth: localStorage.getItem("urlapp_gaops_auth") }));
  check("GA Ops: 읽기 거부(403)는 위젯에 이유를 보이고 토큰을 지움", w4.w.note === "GA Ops 연결 실패 (읽기 403)"
    && w4.w.rows.length === 0 && w4.auth === null, w4);

  // 범용 위젯 (사이트가 내용을 정함)
  const sp = await browser.newPage();
  await sp.goto(base + "jarvis");
  await sp.evaluate((tok) => {
    window.__w = [];
    window.UrlAppBridge = { widget: (t, j) => { if (t === tok) window.__w.push(JSON.parse(j)); } };
  }, TOKEN);
  spMode = "none";
  await sp.evaluate(asset(SITEP).replace('"__URLAPP_TOKEN__"', JSON.stringify(TOKEN)));
  await sp.waitForFunction(() => __w.length === 1, null, { timeout: 5000 });
  const s1 = await sp.evaluate(() => __w[0]);
  check("범용: 사이트가 위젯 정보를 안 주면 '서버 연결됨'만", s1.rows.length === 1 && s1.rows[0].value === "연결됨"
    && s1.note.length <= 30, s1);
  spMode = "json";
  await sp.evaluate(() => window.__urlappWidgetRun());
  await sp.waitForFunction(() => __w.length === 2, null, { timeout: 5000 });
  const s2 = await sp.evaluate(() => __w[1]);
  check("범용: /urlapp-widget.json 의 내용을 그대로", s2.title === "오늘의 자비스" && s2.note === "할 일 3"
    && JSON.stringify(s2.rows.map((r) => [r.label, r.value, r.tone])) === JSON.stringify([["일정", "14:00 회의", "pos"], ["서버", "정상", ""]]), s2);
  for (const mode of ["401", "empty"]) {
    spMode = mode;
    await sp.evaluate(() => window.__urlappWidgetRun());
    await sp.waitForTimeout(600);
    check(`범용: ${mode === "401" ? "로그인 전(401)" : "줄 없음"}이면 위젯을 바꾸지 않음`, (await sp.evaluate(() => __w.length)) === 2);
  }
  spMode = "none";
  const sp2 = await browser.newPage();
  await sp2.goto(base + "jarvis");
  await sp2.evaluate((tok) => {
    window.__w = [];
    window.UrlAppBridge = { widget: (t, j) => { if (t === tok) window.__w.push(JSON.parse(j)); } };
    window.urlappWidget = async () => ({ title: "사이트 함수", rows: [{ label: "a", value: "b" }] });
  }, TOKEN);
  await sp2.evaluate(asset(SITEP).replace('"__URLAPP_TOKEN__"', JSON.stringify(TOKEN)));
  await sp2.waitForFunction(() => __w.length === 1, null, { timeout: 5000 });
  check("범용: 사이트가 window.urlappWidget 을 두면 그것이 먼저", (await sp2.evaluate(() => __w[0].title)) === "사이트 함수");
  spMode = "tasks";
  const sp3 = await browser.newPage();
  await sp3.goto(base + "jarvis");
  await sp3.evaluate((tok) => {
    document.title = "CHARLES";
    window.__w = [];
    window.UrlAppBridge = { widget: (t, j) => { if (t === tok) window.__w.push(JSON.parse(j)); } };
  }, TOKEN);
  await sp3.evaluate(asset(SITEP).replace('"__URLAPP_TOKEN__"', JSON.stringify(TOKEN)));
  await sp3.waitForFunction(() => __w.length === 1, null, { timeout: 8000 });
  const s3 = await sp3.evaluate(() => __w[0]);
  check("범용: 위젯 정보가 없으면 작업 목록(/api/tasks)에서 진행 중인 것 먼저", s3.title === "CHARLES · 진행 중 3"
    && JSON.stringify(s3.rows.map((r) => [r.label, r.value, r.tone])) === JSON.stringify([
      ["블로그 글 작성", "60%", "pos"], ["메일 정리", "failed", "neg"], ["자료 조사", "35%", "pos"], ["영상 편집", "완료", ""]]), s3);
  spMode = "none";
  const sp4 = await browser.newPage();
  await sp4.goto(base + "jarvis-bars");
  await sp4.evaluate((tok) => {
    window.__w = [];
    window.UrlAppBridge = { widget: (t, j) => { if (t === tok) window.__w.push(JSON.parse(j)); } };
  }, TOKEN);
  await sp4.evaluate(asset(SITEP).replace('"__URLAPP_TOKEN__"', JSON.stringify(TOKEN)));
  await sp4.waitForFunction(() => __w.length === 1, null, { timeout: 8000 });
  const s4 = await sp4.evaluate(() => __w[0]);
  check("범용: 그것도 없으면 화면의 진행 막대와 이름 (늦게 그려져도)", s4.title === "CHARLES · 진행 중 2"
    && JSON.stringify(s4.rows.map((r) => [r.label, r.value])) === JSON.stringify([["보고서 정리", "75%"], ["코드 리뷰", "40%"]]), s4);
  // CHARLES(자비스) 위젯: 기존 API 만 읽어 진행 중인 작업 요약 + 알림
  cjMode = "jobs";
  const cj = await browser.newPage();
  await cj.goto(base + "jarvis");
  await cj.evaluate((tok) => {
    window.__w = [];
    window.UrlAppBridge = { widget: (t, j) => { if (t === tok) window.__w.push(JSON.parse(j)); } };
  }, TOKEN);
  await cj.evaluate(asset(CHARLES).replace('"__URLAPP_TOKEN__"', JSON.stringify(TOKEN)));
  await cj.waitForFunction(() => __w.length === 1, null, { timeout: 8000 });
  const c1 = await cj.evaluate(() => __w[0]);
  check("CHARLES: 확인 필요(승인 대기·발행 대기) 먼저 → 하는 중(진행률 예상·경과 시간), 제목은 작업+요청 수, 아래 줄",
    c1.title === "CHARLES · 진행 중 4" && JSON.stringify(c1.rows.map((r) => [r.label, r.value, r.tone])) === JSON.stringify([
      ["메일 정리해줘", "승인 대기", "neg"], ["발행 대기 · 스탠리 퀜처 H2.0 텀블러", "허용 필요", "neg"],
      ["릴스 만들기 · 장면 3/8", "62% 예상", "pos"], ["자료 조사", "진행 12분", "pos"]])
      && c1.note === "확인 필요 2 · 대기 1 · 안 본 결과 2", c1);
  check("CHARLES: 알림 목록(승인 대기·요청 끝·발행 대기·작업 끝·오류)이 앱으로 넘어감", JSON.stringify(c1.notify) === JSON.stringify([
    { id: "bbbbbbbbbbbb:wait", title: "승인이 필요해요", body: "메일 정리해줘", open: "/" },
    { id: "req29:done", title: "요청 끝", body: "병맛노래 요청 #29", open: "/" },
    { id: "pub:jarvis_kv_20261120_143902", title: "발행 허용이 필요해요", body: "스탠리 퀜처 H2.0 텀블러", open: "/" },
    { id: "cccccccccccc:done", title: "작업 끝", body: "블로그 글", open: "/" },
    { id: "dddddddddddd:error", title: "작업 오류", body: "유튜브 업로드", open: "/" }]), c1.notify);
  cjMode = "music";
  await cj.evaluate(() => window.__urlappWidgetRun());
  await cj.waitForFunction(() => __w.length === 2, null, { timeout: 5000 });
  const cm = await cj.evaluate(() => __w[1]);
  check("CHARLES: 작업창의 '지금 하는 일'(요청함의 음악 요청 #30)도 보임 — 채팅 작업이 없어도", cm.title === "CHARLES · 진행 중 1"
    && JSON.stringify(cm.rows.map((r) => [r.label, r.value])) === JSON.stringify([["음악 요청 #30", "진행 3분"]]), cm);
  await cj.evaluate(() => { __w.length = 1; });
  check("CHARLES: 읽기만 함 (GET 만, 승인 대기 작업의 기록은 읽지 않음)", cjReqs.every((r) => r.startsWith("GET "))
    && !cjReqs.some((r) => r.includes("bbbbbbbbbbbb")), cjReqs);
  cjMode = "idle";
  await cj.evaluate(() => window.__urlappWidgetRun());
  await cj.waitForFunction(() => __w.length === 2, null, { timeout: 5000 });
  const c2 = await cj.evaluate(() => __w[1]);
  check("CHARLES: 할 일이 없으면 '지금 하는 일 없음'", c2.title === "CHARLES · 쉬는 중" && c2.rows.length === 1
    && c2.rows[0].value === "없음" && c2.notify.length === 0, c2);
  cjMode = "site";
  cjReqs.length = 0;
  await cj.evaluate(() => window.__urlappWidgetRun());
  await cj.waitForFunction(() => __w.length === 3, null, { timeout: 5000 });
  const cs = await cj.evaluate(() => __w[2]);
  check("CHARLES: 자비스가 /urlapp-widget.json 을 주면 그 값(작업창과 같은 83%)을 그대로, 알림은 자비스 목록에 맡김",
    cs.title === "자비스 · 진행 중 1" && cs.rows.length === 1 && cs.rows[0].value === "83%" && cs.notify.length === 0
      && !cjReqs.some((r) => r.includes("/api/")), { cs, cjReqs });
  check("CHARLES: 할 일 목록을 줄 곳이 없으면 두 번째 위젯은 안 바꿈 (첫 위젯은 그대로)", !("alt" in cs) && !("alt" in c1), cs.alt);
  // 두 번째 위젯 '자비스 할 일' (2026-11-28): 자비스가 /urlapp-todo.json 을 주면 그대로, 없으면 스마트 메모장의 안 끝난 일
  cjTodo = "site";
  await cj.evaluate(() => { __w.length = 3; window.__urlappWidgetRun(); });
  await cj.waitForFunction(() => __w.length === 4, null, { timeout: 5000 });
  const ct = await cj.evaluate(() => __w[3]);
  check("할 일 위젯: 자비스가 /urlapp-todo.json 을 주면 그 줄 그대로, 제목은 'TO-DO LIST'·개수는 아래 줄 '미완료 N' (첫 위젯은 예전과 같음)",
    ct.alt && ct.alt.title === "TO-DO LIST" && ct.alt.note === "미완료 3 · 오늘 1" && ct.alt.rows.length === 3 && ct.alt.rows[0].label === "전기차 보고"
      && ct.title === "자비스 · 진행 중 1" && ct.rows[0].value === "83%", ct);
  cjTodo = "memos";
  await cj.evaluate(() => { __w.length = 3; window.__urlappWidgetRun(); });
  await cj.waitForFunction(() => __w.length === 4, null, { timeout: 5000 });
  const cmm = await cj.evaluate(() => __w[3]);
  check("할 일 위젯(대체): 메모장의 안 끝난 일만, 지난 것·가까운 것 먼저, 기한 없는 것은 뒤, '메모장 기준'",
    cmm.alt && cmm.alt.title === "TO-DO LIST" && JSON.stringify(cmm.alt.rows.map((r) => [r.label, r.value, r.tone])) === JSON.stringify([
      ["📅 검진 예약", "1일 지남", "neg"], ["🧾 택시비 정산", "오늘", "pos"], ["💼 전기차 보고", "D-3", ""], ["📝 세탁소 찾기", "개인·기타", ""]])
      && cmm.alt.note === "미완료 4 · 지남 1 · 오늘 1 · 메모장 기준", cmm.alt);
  cjTodo = "todos";
  await cj.evaluate(() => { __w.length = 3; window.__urlappWidgetRun(); });
  await cj.waitForFunction(() => __w.length === 4, null, { timeout: 5000 });
  const ctd = await cj.evaluate(() => __w[3]);
  check("할 일 위젯: '메모장 · TO-DO' 의 할 일 목록(/api/todos)을 찾으면 그것 (끝난 것 빼고, 오늘 먼저, '메모장 기준' 없음)",
    ctd.alt && ctd.alt.title === "TO-DO LIST" && JSON.stringify(ctd.alt.rows.map((r) => [r.label, r.value, r.tone])) === JSON.stringify([
      ["테스트", "오늘", "pos"], ["엄마 점심", "D-1", ""]]) && ctd.alt.note === "미완료 2 · 오늘 1", ctd.alt);
  cjTodo = "many";
  await cj.evaluate(() => { __w.length = 3; window.__urlappWidgetRun(); });
  await cj.waitForFunction(() => __w.length === 4, null, { timeout: 5000 });
  const cmy = await cj.evaluate(() => __w[3]);
  check("할 일 위젯: 많으면 넘겨 보는 목록에 30줄까지 (나머지는 '외 N개')", cmy.alt && cmy.alt.rows.length === 30
    && cmy.alt.rows[29].label === "할 일 30" && cmy.alt.note.includes("외 10개"), [cmy.alt && cmy.alt.rows.length, cmy.alt && cmy.alt.note]);
  cjTodo = "none";
  cjMode = "siteIdle";
  await cj.evaluate(() => { __w.length = 2; window.__urlappWidgetRun(); });
  await cj.waitForFunction(() => __w.length === 3, null, { timeout: 5000 });
  const ci = await cj.evaluate(() => __w[2]);
  check("CHARLES: 자비스가 할 일 없음(rows 비움)을 주면 자비스 제목 그대로 + '지금 하는 일 없음'",
    ci.title === "자비스 · 쉬는 중" && ci.rows.length === 1 && ci.rows[0].value === "없음" && ci.notify.length === 0, ci);
  await cj.evaluate(() => { __w.length = 2; });
  cjMode = "login";
  await cj.evaluate(() => window.__urlappWidgetRun());
  await cj.waitForTimeout(600);
  check("CHARLES: 로그인이 풀리면(401) 위젯을 바꾸지 않음", (await cj.evaluate(() => __w.length)) === 2);
  cjMode = "off";
  check("범용: 스크립트에 사이트 주소가 없음 (상대 주소만)", !/https?:\/\//.test(SITEP.replace(/^\/\/.*$/gm, "")));

  await browser.close();
  server.close();
  const ok = results.filter(Boolean).length;
  console.log(`widget 시험: ${ok}개 통과, ${results.length - ok}개 실패`);
  process.exit(results.length === ok ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
