/// <reference types="node" />
// 웹사이트 중계(api/coupang.js)를 실제 Node 서버에 올리고, 그 뒤에 서명을 검사하는 가짜 쿠팡 게이트웨이를 둔다.
// 브라우저 → /api/coupang → 쿠팡 경로로 그대로 전달되는지, 서명이 깨지지 않는지 확인한다.
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';
import { after, before, test } from 'node:test';

import { API_PATH, CoupangApiError, createClient } from '../src/api/client';

const ACCESS = 'proxy-access-key-01';
const SECRET = 'proxy-secret-key-01';
let gateway: Server;
let site: Server;
let siteUrl = '';
const upstreamSeen: { method: string; url: string; body: string; secretSeen: boolean }[] = [];

const listen = (s: Server) => new Promise<string>((r) => s.listen(0, '127.0.0.1', () => r(`http://127.0.0.1:${(s.address() as AddressInfo).port}`)));

before(async () => {
  gateway = createServer((req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      const raw = JSON.stringify(req.headers) + body;
      upstreamSeen.push({ method: String(req.method), url: String(req.url), body, secretSeen: raw.includes(SECRET) });
      const m = String(req.headers.authorization).match(/signed-date=(\w+), signature=(\w+)$/);
      const [path, query = ''] = String(req.url).split('?');
      const ok = m && createHmac('sha256', SECRET).update(m[1] + req.method + path + query).digest('hex') === m[2];
      res.setHeader('Content-Type', 'application/json');
      if (!ok) {
        res.statusCode = 401;
        return res.end(JSON.stringify({ code: 'ERROR', message: 'Specified key is not registered.' }));
      }
      if (path === `${API_PATH}/deeplink`) {
        const { coupangUrls } = JSON.parse(body) as { coupangUrls: string[] };
        return res.end(JSON.stringify({ rCode: '0', data: coupangUrls.map((u) => ({ originalUrl: u, shortenUrl: 'https://link.coupang.com/a/px1' })) }));
      }
      res.end(
        JSON.stringify({
          rCode: '0',
          data: { landingUrl: 'https://link.coupang.com/re/x', productData: [{ productId: 1, productName: '상품', productPrice: 1000, productUrl: 'https://link.coupang.com/re/1' }] },
        }),
      );
    });
  });
  process.env.COUPANG_API_BASE = await listen(gateway);
  const mod = (await import('../api/coupang.js')) as { default: (req: IncomingMessage, res: ServerResponse) => Promise<void> };
  const handler = mod.default;
  site = createServer((req, res) => {
    if (req.url === '/api/coupang') return void handler(req, res);
    res.statusCode = 404;
    res.end('<html>not found</html>');
  });
  siteUrl = await listen(site);
});

after(() => {
  site.close();
  gateway.close();
});

const viaProxy = () => createClient({ accessKey: ACCESS, secretKey: SECRET, proxyUrl: `${siteUrl}/api/coupang` });

test('중계를 거쳐도 서명이 그대로 통과한다 (한글 검색어)', async () => {
  upstreamSeen.length = 0;
  const r = await viaProxy().searchProducts('캠핑 의자', { subId: 'blog' });
  assert.equal(r.products[0].productName, '상품');
  assert.equal(upstreamSeen[0].url, `${API_PATH}/products/search?keyword=%EC%BA%A0%ED%95%91%20%EC%9D%98%EC%9E%90&limit=10&subId=blog`);
  assert.equal(upstreamSeen[0].secretSeen, false, 'Secret Key는 중계로 가지 않는다');
});

test('딥링크 POST 본문도 그대로 전달', async () => {
  upstreamSeen.length = 0;
  const r = await viaProxy().deeplink(['https://www.coupang.com/vp/products/5'], 'insta');
  assert.equal(r[0].shortenUrl, 'https://link.coupang.com/a/px1');
  assert.equal(upstreamSeen[0].method, 'POST');
  assert.deepEqual(JSON.parse(upstreamSeen[0].body), { coupangUrls: ['https://www.coupang.com/vp/products/5'], subId: 'insta' });
});

test('쿠팡의 401은 그대로 돌려줘서 키 오류로 보인다', async () => {
  const c = createClient({ accessKey: ACCESS, secretKey: 'wrong', proxyUrl: `${siteUrl}/api/coupang` });
  await assert.rejects(c.goldbox(), (e: unknown) => e instanceof CoupangApiError && e.kind === 'auth');
});

test('중계는 파트너스 API 경로와 서명 있는 요청만 받는다', async () => {
  const call = (headers: Record<string, string>, method = 'GET') => fetch(`${siteUrl}/api/coupang`, { method, headers });
  const sig = { authorization: 'CEA algorithm=HmacSHA256, access-key=a, signed-date=x, signature=y' };
  assert.equal((await call({ ...sig, 'x-coupang-path': '/v2/providers/seller_api/apis/x' })).status, 400);
  assert.equal((await call({ ...sig, 'x-coupang-path': `${API_PATH}/../../seller` })).status, 400);
  assert.equal((await call({ ...sig })).status, 400);
  assert.equal((await call({ 'x-coupang-path': `${API_PATH}/products/goldbox` })).status, 401);
  assert.equal((await call({ ...sig, 'x-coupang-path': `${API_PATH}/products/goldbox` }, 'PUT')).status, 405);
});

test('중계가 없는 곳(일반 웹 호스팅)에서는 알아듣는 안내를 낸다', async () => {
  const c = createClient({ accessKey: ACCESS, secretKey: SECRET, proxyUrl: `${siteUrl}/api/none` });
  await assert.rejects(c.goldbox(), (e: unknown) => e instanceof CoupangApiError && /중계 서버/.test(e.message));
});
