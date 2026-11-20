// 웹사이트용 네이버 API 중계 (Vercel 서버리스 함수).
//
// 브라우저는 보안 정책(CORS) 때문에 네이버 API를 직접 부를 수 없다. 그래서 웹사이트는 같은 주소의
// /api/naver 로 요청을 보내고, 이 함수가 네이버에 그대로 전달한다.
// - 딱 두 가지만 전달한다: API HUB 쇼핑인사이트(POST /shopping/v1/categories),
//   개발자센터 쇼핑 검색(GET /v1/search/shop.json?...).
// - 키는 요청 헤더로 와서 네이버로 그대로 넘어간다. 아무것도 저장하거나 기록하지 않는다.
const HUB = process.env.NAVER_APIHUB_BASE || 'https://naverapihub.apigw.ntruss.com';
const OPEN = process.env.NAVER_OPENAPI_BASE || 'https://openapi.naver.com';
const TREND_PATH = '/shopping/v1/categories';
const SHOP_PATH = '/v1/search/shop.json';
const MAX_BODY = 16 * 1024;

function send(res, status, payload) {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  res.end(JSON.stringify(payload));
}

async function readBody(req) {
  if (req.body !== undefined && req.body !== null) {
    return typeof req.body === 'string' ? req.body : JSON.stringify(req.body);
  }
  let size = 0;
  let text = '';
  const decoder = new TextDecoder();
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_BODY) throw new Error('body too large');
    text += decoder.decode(chunk, { stream: true });
  }
  return text + decoder.decode();
}

function header(req, name) {
  const v = req.headers[name];
  return typeof v === 'string' ? v.trim() : '';
}

/** 목적지 검사: 허용한 두 경로만. 통과하면 { url, method, headers } */
function route(req) {
  const target = header(req, 'x-naver-target');
  const path = header(req, 'x-naver-path');
  if (!path || path.includes('..') || path.includes('//') || /[\s\\#]/.test(path)) return null;
  if (target === 'hub' && path === TREND_PATH && req.method === 'POST') {
    const id = header(req, 'x-ncp-apigw-api-key-id');
    const key = header(req, 'x-ncp-apigw-api-key');
    if (!id || !key) return { error: 'NO_KEY' };
    return { url: HUB + path, method: 'POST', headers: { 'X-NCP-APIGW-API-KEY-ID': id, 'X-NCP-APIGW-API-KEY': key, 'Content-Type': 'application/json' } };
  }
  if (target === 'open' && path.startsWith(`${SHOP_PATH}?`) && req.method === 'GET') {
    const id = header(req, 'x-naver-client-id');
    const key = header(req, 'x-naver-client-secret');
    if (!id || !key) return { error: 'NO_KEY' };
    return { url: OPEN + path, method: 'GET', headers: { 'X-Naver-Client-Id': id, 'X-Naver-Client-Secret': key } };
  }
  return null;
}

async function handler(req, res) {
  if (req.method !== 'GET' && req.method !== 'POST') {
    res.setHeader('Allow', 'GET, POST');
    return send(res, 405, { code: 'METHOD', message: 'GET 또는 POST만 받아요.' });
  }
  const r = route(req);
  if (!r) return send(res, 400, { code: 'BAD_PATH', message: '중계하지 않는 네이버 주소예요 (쇼핑인사이트·쇼핑 검색만).' });
  if (r.error) return send(res, 401, { code: 'NO_KEY', message: '네이버 키 헤더가 없는 요청이에요.' });

  let body;
  if (r.method === 'POST') {
    try {
      body = await readBody(req);
    } catch {
      return send(res, 413, { code: 'BODY', message: '요청 본문이 너무 커요.' });
    }
  }
  let upstream;
  try {
    upstream = await fetch(r.url, { method: r.method, headers: r.headers, body });
  } catch {
    return send(res, 502, { code: 'UPSTREAM', message: '네이버 서버에 연결하지 못했어요.' });
  }
  const text = await upstream.text();
  res.statusCode = upstream.status;
  res.setHeader('Content-Type', upstream.headers.get('content-type') || 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  res.end(text);
}

module.exports = handler;
module.exports.TREND_PATH = TREND_PATH;
module.exports.SHOP_PATH = SHOP_PATH;
