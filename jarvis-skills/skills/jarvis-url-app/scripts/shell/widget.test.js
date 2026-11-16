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
const SLOT = "(/*__WIDGET_FN__*/ null)";
const ASSET = RUNNER.replace(SLOT, "(\n" + LEDGER.trim().replace(/;\s*$/, "") + "\n)"); // url_app.py 의 widget_asset 과 같게
const TOKEN = "t0k3n";

const results = [];
function check(label, cond, detail) {
  results.push(!!cond);
  console.log(`  [${cond ? "통과" : "실패"}] ${label}${!cond && detail ? " — " + JSON.stringify(detail) : ""}`);
}

(async () => {
  let summary = { income: 5000000, fixed: 1200000, variable: 800000, savings: 1000000, investments: 0 };
  const requests = [];
  const server = http.createServer((req, res) => {
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
  check("로그인 전에는 읽지 않음", (await page.evaluate(() => __w.length)) === 0 && requests.length === 0, requests);

  await page.evaluate(() => { sessionStorage.setItem("isAuthenticated", "true"); window.__urlappWidgetRun(); });
  await page.waitForFunction(() => __w.length === 1, null, { timeout: 5000 });
  const w = await page.evaluate(() => __w[0]);
  const now = new Date();
  const m = now.getMonth() + 1;
  check("제목", w.title === `${m}월 우리집 가계부`, w.title);
  check("수입·지출(고정+변동)·저축·차액", JSON.stringify(w.rows.map((r) => [r.label, r.value, r.tone])) === JSON.stringify([
    ["수입", "5,000,000원", "pos"], ["지출", "2,000,000원", "neg"], ["저축", "1,000,000원", ""],
    ["수입 − 지출", "3,000,000원", "pos"]]), w.rows);
  const last = new Date(now.getFullYear(), m, 0).getDate();
  const ym = `${now.getFullYear()}-${String(m).padStart(2, "0")}`;
  check("대시보드와 같은 요청 (이번 달, 전체 사용자)",
    requests[0] === `/api/summary?startDate=${ym}-01&endDate=${ym}-${String(last).padStart(2, "0")}&earnerId=all`, requests);

  // 사이트가 지출을 저장하면(POST) 잠시 뒤 위젯이 다시 읽는다
  await page.evaluate(() => fetch("/api/monthly-details/add-single", { method: "POST", body: "{}" }));
  await page.waitForFunction(() => __w.length >= 2, null, { timeout: 5000 });
  const w2 = await page.evaluate(() => __w[__w.length - 1]);
  check("저장 직후 다시 읽어 지출이 바뀜", w2.rows[1].value === "2,006,560원", w2.rows);
  // GET 은 다시 읽기를 부르지 않는다
  const before = await page.evaluate(() => __w.length);
  await page.evaluate(() => fetch("/api/summary?x=1"));
  await page.waitForTimeout(2200);
  check("GET 요청은 다시 읽기를 부르지 않음", (await page.evaluate(() => __w.length)) === before);

  // 두 번 넣어도 한 번만
  await page.evaluate(ASSET.replace('"__URLAPP_TOKEN__"', JSON.stringify(TOKEN)));
  await page.waitForTimeout(400);
  check("두 번 넣어도 실행기는 하나", (await page.evaluate(() => __w.length)) === before);

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

  await browser.close();
  server.close();
  const ok = results.filter(Boolean).length;
  console.log(`widget 시험: ${ok}개 통과, ${results.length - ok}개 실패`);
  process.exit(results.length === ok ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
