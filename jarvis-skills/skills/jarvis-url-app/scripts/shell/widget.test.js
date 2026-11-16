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
const GAOPS = fs.readFileSync(path.join(__dirname, "..", "..", "widgets", "gaops-tasks.js"), "utf8")
  .replace(/__PARAM_GAOPS_PROJECT__/g, "fake-project").replace(/__PARAM_GAOPS_API_KEY__/g, "FAKE_WEB_KEY");
const FAKE_REF = "abcdefghijklmnopqrst"; // 지어낸 Supabase 프로젝트·키·토큰
const FAKE_KEY = "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoiYW5vbiJ9.fakefakefake";
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
  await page.evaluate(() => Promise.all([fetch("/api/summary?x=1"), fetch("/api/summary?x=2", { method: "HEAD" })]));
  await page.waitForTimeout(2200);
  check("GET·HEAD 요청은 다시 읽기를 부르지 않음", (await page.evaluate(() => __w.length)) === before);

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
  await ga.evaluate((ref) => { localStorage.setItem(`sb-${ref}-auth-token`, JSON.stringify({ access_token: "s" }));
    window.__urlappWidgetRun(); }, FAKE_REF);
  await ga.waitForFunction(() => __w.length === 1, null, { timeout: 5000 });
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

  await browser.close();
  server.close();
  const ok = results.filter(Boolean).length;
  console.log(`widget 시험: ${ok}개 통과, ${results.length - ok}개 실패`);
  process.exit(results.length === ok ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
