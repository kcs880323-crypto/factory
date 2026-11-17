// 웹사이트용 쿠팡 파트너스 API 중계 (Vercel 서버리스 함수).
//
// 브라우저는 보안 정책(CORS) 때문에 쿠팡 API를 직접 부를 수 없을 수 있다. 그래서 웹사이트는
// 같은 주소의 /api/coupang 으로 요청을 보내고, 이 함수가 그대로 쿠팡에 전달한다.
// - 서명은 브라우저에서 이미 끝난 상태로 온다. Secret Key는 여기로 오지 않는다.
// - 쿠팡 파트너스 API 경로(/v2/providers/affiliate_open_api/apis/openapi/...)만 전달한다.
// - 아무것도 저장하거나 기록하지 않는다.
const UPSTREAM = process.env.COUPANG_API_BASE || 'https://api-gateway.coupang.com';
const ALLOWED_PREFIX = '/v2/providers/affiliate_open_api/apis/openapi/';
const MAX_BODY = 64 * 1024;

function send(res, status, payload) {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  res.end(JSON.stringify(payload));
}

async function readBody(req) {
  // Vercel은 JSON 본문을 미리 읽어 req.body에 넣어 준다. 다른 서버에서는 직접 읽는다.
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

async function handler(req, res) {
  if (req.method !== 'GET' && req.method !== 'POST') {
    res.setHeader('Allow', 'GET, POST');
    return send(res, 405, { code: 'METHOD', message: 'GET 또는 POST만 받아요.' });
  }
  const target = req.headers['x-coupang-path'];
  if (
    typeof target !== 'string' ||
    !target.startsWith(ALLOWED_PREFIX) ||
    target.includes('..') ||
    target.includes('//') ||
    /[\s\\#]/.test(target)
  ) {
    return send(res, 400, { code: 'BAD_PATH', message: '쿠팡 파트너스 API 경로가 아니에요.' });
  }
  const auth = req.headers.authorization;
  if (typeof auth !== 'string' || !auth.startsWith('CEA ')) {
    return send(res, 401, { code: 'NO_AUTH', message: '서명(Authorization)이 없는 요청이에요.' });
  }

  let body;
  if (req.method === 'POST') {
    try {
      body = await readBody(req);
    } catch {
      return send(res, 413, { code: 'BODY', message: '요청 본문이 너무 커요.' });
    }
  }

  let upstream;
  try {
    upstream = await fetch(UPSTREAM + target, {
      method: req.method,
      headers: { Authorization: auth, 'Content-Type': 'application/json;charset=UTF-8' },
      body,
    });
  } catch {
    return send(res, 502, { code: 'UPSTREAM', message: '쿠팡 서버에 연결하지 못했어요.' });
  }
  const text = await upstream.text();
  res.statusCode = upstream.status;
  res.setHeader('Content-Type', upstream.headers.get('content-type') || 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  res.end(text);
}

module.exports = handler;
module.exports.ALLOWED_PREFIX = ALLOWED_PREFIX;
