// download.js 시험 (개발용). 안드로이드 WebView 와 같은 엔진인 크로미움(Playwright)에서
// 가짜 UrlAppBridge 를 두고, 실제 사이트에서 본 다운로드 코드 모양들을 그대로 실행해 결과를 확인한다.
//   node download.test.js        (Playwright 와 크로미움이 있어야 함)
"use strict";
const http = require("http");
const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");

const HELPER = fs.readFileSync(path.join(__dirname, "download.js"), "utf8");
const TOKEN = "t0k3n";

// 앱 쪽(Bridge)을 흉내 낸다: 조각을 모아 파일로, 다운로드·열기 요청은 기록만.
const FAKE_BRIDGE = `
window.__calls = []; window.__files = {}; window.__saved = [];
window.UrlAppBridge = {
  begin(token, name, mime, size) { __calls.push(['begin', token, name, mime, size]);
    if (token !== '${TOKEN}') return ''; const id = 'f' + __calls.length; __files[id] = {name, mime, size, parts: []}; return id; },
  chunk(token, id, b64) { if (token !== '${TOKEN}' || !__files[id]) return false; __files[id].parts.push(b64); return true; },
  end(token, id) { const f = __files[id]; const bin = f.parts.map(p => atob(p)).join('');
    __saved.push({name: f.name, mime: f.mime, size: f.size, length: bin.length, text: bin.length < 5000 ? bin : null,
                  sum: Array.from(bin).reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 7)}); },
  fail(token, msg) { __calls.push(['fail', token, msg]); },
  download(token, url, name) { __calls.push(['download', token, url, name]); },
  openUrl(token, url) { __calls.push(['openUrl', token, url]); },
};`;

const results = [];
function check(label, cond, detail) {
  results.push(!!cond);
  console.log(`  [${cond ? "통과" : "실패"}] ${label}${!cond && detail ? " — " + JSON.stringify(detail) : ""}`);
}

(async () => {
  const server = http.createServer((req, res) => {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end("<!doctype html><html><body><p>시험 페이지</p></body></html>");
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${server.address().port}/`;
  const browser = await chromium.launch();
  const page = await browser.newPage();
  const fresh = async () => {
    await page.goto(base);
    await page.evaluate(FAKE_BRIDGE);
    // WebView 에는 Web Share 가 없다
    await page.evaluate(() => { delete Navigator.prototype.share; delete Navigator.prototype.canShare; });
  };
  const inject = () => page.evaluate(HELPER.replace('"__URLAPP_TOKEN__"', JSON.stringify(TOKEN)));
  const wait = (ms) => page.waitForTimeout(ms);

  console.log("download.js 시험 (크로미움)");

  // 1) 게시판 백업: JSON Blob → <a download> 클릭 → 바로 removeChild·revokeObjectURL (사이트 코드 그대로)
  await fresh(); await inject();
  await page.evaluate(() => {
    const ae = { version: "1.1", tables: { team_board: [{ id: 1, title: "공지 \"따옴표\" & 한글" }] } };
    const ye = new Blob([JSON.stringify(ae, null, 2)], { type: "application/json" }), we = URL.createObjectURL(ye),
      Ae = document.createElement("a");
    Ae.href = we; Ae.download = "board_backup_2026-11-16.json"; document.body.appendChild(Ae); Ae.click();
    document.body.removeChild(Ae); URL.revokeObjectURL(we);
    window.__expect1 = JSON.stringify(ae, null, 2);
  });
  await wait(300);
  let r = await page.evaluate(() => ({ saved: __saved, expect: __expect1 }));
  check("게시판 백업: 파일 1개 저장", r.saved.length === 1, r.saved);
  check("게시판 백업: 이름·형식", r.saved[0] && r.saved[0].name === "board_backup_2026-11-16.json" &&
    r.saved[0].mime === "application/json", r.saved[0]);
  const utf8 = Buffer.from(r.expect, "utf8").toString("latin1");
  check("게시판 백업: 내용이 바이트 단위로 같음 (revoke 뒤에도)", r.saved[0] && r.saved[0].text === utf8);

  // 2) 자산 백업: Android+share 없음 → window.open(공개 주소) → null → <a download> 대체 (사이트 코드 그대로)
  await fresh(); await inject();
  await page.evaluate(async () => {
    const N = "https://abc.supabase.co/storage/v1/object/public/backups/team/1731_report.pdf", M = "1731_report.pdf";
    if (!window.open(N, "_blank")) {
      const B = document.createElement("a");
      B.href = N; B.download = M; B.style.display = "none"; document.body.appendChild(B); B.click();
      setTimeout(() => document.body.removeChild(B), 100);
    }
  });
  await wait(700);
  r = await page.evaluate(() => __calls);
  check("자산 백업: 다운로드 관리자에 이름과 함께 넘김",
    r.some((c) => c[0] === "download" && c[2].endsWith("/1731_report.pdf") && c[3] === "1731_report.pdf"), r);
  check("자산 백업: 앱 화면을 그 주소로 바꾸지 않음 (openUrl 없음)", !r.some((c) => c[0] === "openUrl"), r);
  check("자산 백업: 페이지가 그대로", (await page.url()) === base);

  // 3) 큰 파일 (2.5MB, 여러 조각)
  await fresh(); await inject();
  await page.evaluate(() => {
    const n = 2.5 * 1024 * 1024, b = new Uint8Array(n);
    let x = 12345; for (let i = 0; i < n; i++) { x = (x * 1103515245 + 12345) >>> 0; b[i] = x >>> 24; }
    window.__expectSum = Array.from(b).reduce((a, c) => (a * 31 + c) >>> 0, 7);
    const u = URL.createObjectURL(new Blob([b], { type: "application/zip" }));
    const a = document.createElement("a"); a.href = u; a.download = "big.zip"; document.body.appendChild(a); a.click(); a.remove();
  });
  await page.waitForFunction(() => __saved.length === 1, null, { timeout: 15000 });
  r = await page.evaluate(() => ({ s: __saved[0], sum: __expectSum }));
  check("큰 파일: 길이와 내용 일치", r.s.length === 2.5 * 1024 * 1024 && r.s.sum === r.sum, { len: r.s.length });

  // 4) 도우미를 넣기 전에 만든 blob 도 (fetch 로) 받는다
  await fresh();
  await page.evaluate(() => { window.__early = URL.createObjectURL(new Blob(["early"], { type: "text/plain" })); });
  await inject();
  await page.evaluate(() => {
    const a = document.createElement("a"); a.href = __early; a.download = "early.txt"; document.body.appendChild(a);
    a.click(); a.remove(); URL.revokeObjectURL(__early);
  });
  await wait(300);
  r = await page.evaluate(() => __saved);
  check("도우미 전에 만든 blob + 바로 revoke", r.length === 1 && r[0].text === "early", r);

  // 5) 사이트가 스스로 막은 클릭은 건드리지 않는다
  await fresh(); await inject();
  await page.evaluate(() => {
    const a = document.createElement("a"); a.href = URL.createObjectURL(new Blob(["x"])); a.download = "x.txt";
    a.addEventListener("click", (e) => e.preventDefault()); document.body.appendChild(a); a.click();
  });
  await wait(300);
  r = await page.evaluate(() => ({ saved: __saved, calls: __calls }));
  check("사이트가 preventDefault 한 링크는 그대로 둠", r.saved.length === 0 && r.calls.length === 0, r);

  // 6) 그냥 새 창 열기: 잠시 뒤 openUrl (앱이 자기 사이트면 앱 안, 아니면 브라우저로 연다)
  await fresh(); await inject();
  const ret = await page.evaluate(() => window.open("https://example.com/page", "_blank"));
  await wait(700);
  r = await page.evaluate(() => __calls);
  check("window.open 은 null 을 돌려주고", ret === null);
  check("잠시 뒤 openUrl 로 연다", r.length === 1 && r[0][0] === "openUrl" && r[0][2] === "https://example.com/page", r);

  // 7) data: 다운로드, 8) blob 을 window.open 으로만 연 경우
  await fresh(); await inject();
  await page.evaluate(() => {
    const a = document.createElement("a"); a.href = "data:text/csv;base64," + btoa("a,b\n1,2\n"); a.download = "t.csv";
    document.body.appendChild(a); a.click();
    window.open(URL.createObjectURL(new Blob(["opened"], { type: "text/plain" })), "_blank");
  });
  await wait(800);
  r = await page.evaluate(() => __saved);
  check("data: 링크 저장", r.some((s) => s.name === "t.csv" && s.text === "a,b\n1,2\n"), r);
  check("window.open(blob) 만 한 경우도 저장", r.some((s) => s.text === "opened"), r);

  // 9) 두 번 넣어도 한 번만 동작
  await fresh(); await inject(); await inject();
  await page.evaluate(() => {
    const a = document.createElement("a"); a.href = URL.createObjectURL(new Blob(["once"])); a.download = "o.txt";
    document.body.appendChild(a); a.click();
  });
  await wait(300);
  r = await page.evaluate(() => __saved);
  check("두 번 넣어도 한 번만 저장", r.length === 1, r);

  await browser.close();
  server.close();
  const ok = results.filter(Boolean).length;
  console.log(`download.js 시험: ${ok}개 통과, ${results.length - ok}개 실패`);
  process.exit(results.length === ok ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
