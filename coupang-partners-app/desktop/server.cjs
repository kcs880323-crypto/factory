// 파트너스 매니저 PC판 서버 — 이 PC 안에서만 열린다 (http://127.0.0.1:8790).
// - web/        앱 화면 (웹 빌드)
// - /api/coupang 쿠팡 파트너스 API 중계 (서명은 화면에서 끝내고, 여기서는 그대로 전달만)
// - /api/naver   네이버 API 중계 (쇼핑인사이트·쇼핑 검색 두 주소만)
// 키·링크는 이 서버에 저장하지 않는다 (앱 창의 브라우저 저장소에만 있다). 요청 내용·키는 기록하지 않는다.
// 앱 창을 닫고 10분이 지나면 스스로 꺼진다 (창이 열려 있는 동안 화면이 1분마다 신호를 보낸다).
// 필요한 것: Node.js 18 이상 (내장 fetch). 다른 패키지 없음.
'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const PORT = Number(process.env.PM_PORT || 8790);
const HOST = '127.0.0.1';
const ROOT = __dirname;
const WEB = path.join(ROOT, 'web');
const IDLE_MS = Number(process.env.PM_IDLE_MS || 10 * 60 * 1000);
const VERSION = (() => {
  try {
    return fs.readFileSync(path.join(ROOT, 'VERSION.txt'), 'utf8').trim();
  } catch {
    return 'dev';
  }
})();

const major = Number(process.versions.node.split('.')[0]);
if (major < 18 || typeof fetch !== 'function') {
  console.error(`Node.js 18 이상이 필요해요 (지금 ${process.versions.node}). https://nodejs.org 에서 LTS를 설치해 주세요.`);
  process.exit(1);
}

// 설치 묶음에는 ./api, 저장소(개발·시험)에서는 ../api 에 있다
const API_DIR = fs.existsSync(path.join(ROOT, 'api', 'coupang.js')) ? path.join(ROOT, 'api') : path.join(ROOT, '..', 'api');
const coupang = require(path.join(API_DIR, 'coupang.js'));
const naver = require(path.join(API_DIR, 'naver.js'));
const agent = require('./claudeAgent.cjs');

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.svg': 'image/svg+xml',
  '.ttf': 'font/ttf',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.webp': 'image/webp',
};
const HOSTS = new Set([`127.0.0.1:${PORT}`, `localhost:${PORT}`]);
const ORIGINS = new Set([`http://127.0.0.1:${PORT}`, `http://localhost:${PORT}`]);

let lastSeen = Date.now();
const touch = () => {
  lastSeen = Date.now();
};

function send(res, status, body, type = 'text/plain; charset=utf-8') {
  res.statusCode = status;
  res.setHeader('Content-Type', type);
  res.setHeader('Cache-Control', 'no-store');
  res.end(body);
}

function serveFile(req, res, pathname) {
  let rel;
  try {
    rel = decodeURIComponent(pathname);
  } catch {
    return send(res, 400, 'bad path');
  }
  let file = path.resolve(WEB, `.${rel}`);
  if (file !== WEB && !file.startsWith(WEB + path.sep)) return send(res, 403, 'forbidden');
  let ok = false;
  try {
    ok = fs.statSync(file).isFile();
  } catch {
    ok = false;
  }
  if (!ok) {
    // 확장자가 없는 주소는 앱 화면 경로 (예: /settings) → index.html
    if (path.extname(rel)) return send(res, 404, 'not found');
    file = path.join(WEB, 'index.html');
  }
  const ext = path.extname(file).toLowerCase();
  res.statusCode = 200;
  res.setHeader('Content-Type', TYPES[ext] || 'application/octet-stream');
  res.setHeader('Cache-Control', ext === '.html' ? 'no-cache' : 'public, max-age=31536000, immutable');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  if (req.method === 'HEAD') return res.end();
  fs.createReadStream(file).pipe(res);
}

const server = http.createServer((req, res) => {
  // 이 PC의 앱 창에서 온 요청만 받는다 (다른 웹사이트가 몰래 부르는 것·DNS 리바인딩 막기)
  if (!HOSTS.has(String(req.headers.host || ''))) return send(res, 403, 'forbidden host');
  const origin = req.headers.origin;
  if (origin && !ORIGINS.has(String(origin))) return send(res, 403, 'forbidden origin');

  const u = new URL(req.url || '/', `http://${HOST}:${PORT}`);
  if (u.pathname === '/__health') return send(res, 200, JSON.stringify({ app: 'partners-manager', version: VERSION }), 'application/json; charset=utf-8');
  if (u.pathname === '/__alive') {
    touch();
    return send(res, 204, '');
  }
  if (u.pathname === '/__quit' && req.method === 'POST') {
    send(res, 200, 'bye');
    setTimeout(() => process.exit(0), 100);
    return;
  }
  if (u.pathname === '/api/coupang') {
    touch();
    return void Promise.resolve(coupang(req, res)).catch(() => send(res, 500, '{"code":"SERVER"}', 'application/json'));
  }
  if (u.pathname === '/api/naver') {
    touch();
    return void Promise.resolve(naver(req, res)).catch(() => send(res, 500, '{"code":"SERVER"}', 'application/json'));
  }
  if (u.pathname.startsWith('/api/claude/')) {
    touch();
    return void handleClaude(req, res, u).catch((e) => sendJson(res, 500, { code: 'SERVER', message: (e && e.message) || '서버 오류' }));
  }
  if (req.method !== 'GET' && req.method !== 'HEAD') return send(res, 405, 'method not allowed');
  touch();
  serveFile(req, res, u.pathname);
});

function sendJson(res, status, obj) {
  send(res, status, JSON.stringify(obj), 'application/json; charset=utf-8');
}

async function readJson(req) {
  let text = '';
  for await (const chunk of req) {
    text += chunk;
    if (text.length > 4096) throw new Error('body too large');
  }
  try {
    return text ? JSON.parse(text) : {};
  } catch {
    return {};
  }
}

/**
 * Claude로 상품 찾기 (이 PC의 Claude Code). 앱 화면만 부르도록 사용자 정의 헤더(X-PM: 1)를 요구한다 —
 * 다른 웹사이트는 이 헤더를 붙이면 사전 요청(preflight)이 필요해지는데, 여기서는 그 요청을 허락하지 않는다.
 */
async function handleClaude(req, res, u) {
  if (req.headers['x-pm'] !== '1') return sendJson(res, 403, { code: 'FORBIDDEN', message: '앱 화면에서만 부를 수 있어요.' });
  const route = u.pathname.slice('/api/claude/'.length);
  const post = req.method === 'POST';
  try {
    if (route === 'status' && !post) return sendJson(res, 200, await agent.status());
    if (route === 'login' && post) {
      agent.login();
      return sendJson(res, 200, { ok: true });
    }
    if (route === 'browser' && post) {
      const b = await readJson(req);
      const url = b.target === 'partners' ? 'https://partners.coupang.com/' : undefined;
      return sendJson(res, 200, await agent.openBrowser(url));
    }
    if (route === 'search' && post) {
      const b = await readJson(req);
      return sendJson(res, 200, await agent.start(b.q, b.cond));
    }
    if (route === 'job' && !post) return sendJson(res, 200, agent.get(u.searchParams.get('id') || '') || { state: 'none' });
    if (route === 'stop' && post) return sendJson(res, 200, { stopped: agent.stop() });
  } catch (e) {
    const code = (e && e.code) || 'ERROR';
    return sendJson(res, code === 'BUSY' ? 409 : code === 'BAD' ? 400 : 500, { code, message: (e && e.message) || '오류' });
  }
  return sendJson(res, 404, { code: 'NOT_FOUND' });
}

server.on('error', async (e) => {
  if (e && e.code === 'EADDRINUSE') {
    try {
      const r = await fetch(`http://${HOST}:${PORT}/__health`);
      const j = await r.json();
      if (j && j.app === 'partners-manager') {
        console.log('파트너스 매니저 서버가 이미 켜져 있어요.');
        process.exit(0);
      }
    } catch {
      /* 다른 프로그램 */
    }
    console.error(`포트 ${PORT}을(를) 다른 프로그램이 쓰고 있어요. 그 프로그램을 끄거나, 환경변수 PM_PORT로 다른 번호를 정해 주세요.`);
    process.exit(1);
  }
  console.error('서버 오류:', e && e.message);
  process.exit(1);
});

server.listen(PORT, HOST, () => {
  console.log(`파트너스 매니저 서버 ${VERSION} — http://${HOST}:${PORT}/ (이 PC에서만 열림)`);
  console.log('이 창을 닫으면 서버가 꺼져요. 앱 창을 닫고 10분이 지나면 스스로 꺼져요.');
});

process.on('exit', () => agent.shutdown());

setInterval(() => {
  if (agent.running()) touch(); // Claude가 일하는 동안은 끄지 않는다
  if (Date.now() - lastSeen > IDLE_MS) {
    console.log('앱 창이 닫혀 있어 서버를 끕니다.');
    process.exit(0);
  }
}, 30 * 1000).unref();
