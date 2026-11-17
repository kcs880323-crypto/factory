/// <reference types="node" />
// 쿠팡 게이트웨이를 흉내 내는 로컬 서버로 요청 모양과 서명을 확인한다.
// 서버 쪽 서명 검증은 Node crypto로 따로 계산한다 (앱은 js-sha256을 씀).
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { createServer, type IncomingMessage, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { after, before, test } from 'node:test';

import { API_PATH, CoupangApiError, createClient } from '../src/api/client';

const ACCESS = 'test-access-key-0001';
const SECRET = 'test-secret-key-0001';

interface Seen {
  method: string;
  path: string;
  query: string;
  body: string;
}
const seen: Seen[] = [];
let server: Server;
let baseUrl = '';
let mode: 'ok' | '401' | '429' | 'convert-failed' | 'gateway-403' = 'ok';

function verifySignature(req: IncomingMessage): string | null {
  const auth = String(req.headers.authorization ?? '');
  const m = auth.match(/^CEA algorithm=HmacSHA256, access-key=([^,]+), signed-date=(\d{6}T\d{6}Z), signature=([0-9a-f]{64})$/);
  if (!m) return 'bad header format';
  const [, access, date, sig] = m;
  if (access !== ACCESS) return 'unknown access key';
  const [path, query = ''] = String(req.url).split('?');
  const expected = createHmac('sha256', SECRET)
    .update(date + req.method + path + query)
    .digest('hex');
  if (expected !== sig) return 'signature mismatch';
  return null;
}

function product(i: number) {
  return {
    productId: 1000 + i,
    productName: `상품 ${i}`,
    productPrice: '12,900',
    productImage: `https://img.example/${i}.jpg`,
    productUrl: `https://link.coupang.com/re/AFFSDP?itemId=${i}`,
    isRocket: i % 2 === 0,
    isFreeShipping: true,
    rank: i + 1,
    keyword: '노트북',
  };
}

before(async () => {
  server = createServer((req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      const [path, query = ''] = String(req.url).split('?');
      seen.push({ method: String(req.method), path, query, body });
      const send = (status: number, payload: unknown) => {
        res.writeHead(status, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(payload));
      };
      const problem = verifySignature(req);
      if (problem) return send(401, { code: 'ERROR', message: `Specified key is not registered. (${problem})` });
      if (mode === '401') return send(401, { code: 'ERROR', message: 'Specified key is not registered.' });
      if (mode === '429') return send(429, { code: 'ERROR', message: 'Too many requests' });
      if (mode === 'gateway-403') return send(403, { code: 'ERROR', message: 'Forbidden' });

      const ep = path.slice(API_PATH.length);
      const params = new URLSearchParams(query);
      if (ep === '/products/search') {
        return send(200, {
          rCode: '0',
          rMessage: '',
          data: { landingUrl: 'https://link.coupang.com/re/AFFSRP?q=x', productData: [product(0), product(1)] },
        });
      }
      if (ep === '/products/goldbox') return send(200, { rCode: '0', rMessage: '', data: [product(2)] });
      if (ep.startsWith('/products/bestcategories/')) return send(200, { rCode: '0', rMessage: '', data: [product(3)] });
      if (ep === '/deeplink') {
        if (mode === 'convert-failed') return send(200, { rCode: '400', rMessage: 'url convert failed', data: null });
        const { coupangUrls } = JSON.parse(body) as { coupangUrls: string[] };
        return send(200, {
          rCode: '0',
          rMessage: '',
          data: coupangUrls.map((u, i) => ({ originalUrl: u, shortenUrl: `https://link.coupang.com/a/abc${i}` })),
        });
      }
      if (ep.startsWith('/reports/')) {
        const page = Number(params.get('page'));
        const kind = ep.slice('/reports/'.length);
        // clicks는 첫 페이지를 꽉 채워서(1000건) 다음 페이지를 부르게 만든다.
        const count = kind === 'clicks' ? (page === 0 ? 1000 : 3) : page === 0 ? 2 : 0;
        const rows = Array.from({ length: count }, (_, i) => ({
          date: params.get('startDate'),
          subId: 'blog',
          click: 1,
          orderId: 77 + i,
          productId: 5,
          productName: '상품 5',
          quantity: 1,
          gmv: 10000,
          commissionRate: 3,
          commission: 300,
        }));
        return send(200, { rCode: '0', rMessage: '', data: rows });
      }
      send(404, { code: 'ERROR', message: 'not found' });
    });
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', () => r()));
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

after(() => server.close());

const client = (extra: Partial<Parameters<typeof createClient>[0]> = {}) =>
  createClient({ accessKey: ACCESS, secretKey: SECRET, baseUrl, ...extra });

test('상품 검색: 한글 키워드 서명 통과, 가격 문자열을 숫자로', async () => {
  mode = 'ok';
  seen.length = 0;
  const r = await client().searchProducts(' 노트북 ', { limit: 50, subId: 'blog' });
  assert.equal(r.products.length, 2);
  assert.equal(r.products[0].productPrice, 12900);
  assert.equal(r.landingUrl, 'https://link.coupang.com/re/AFFSRP?q=x');
  assert.equal(seen[0].path, `${API_PATH}/products/search`);
  assert.equal(seen[0].query, 'keyword=%EB%85%B8%ED%8A%B8%EB%B6%81&limit=10&subId=blog');
});

test('딥링크: POST 본문과 서명 (쿼리 없음)', async () => {
  mode = 'ok';
  seen.length = 0;
  const r = await client().deeplink(['https://www.coupang.com/vp/products/1', ' '], 'insta');
  assert.deepEqual(r, [
    { originalUrl: 'https://www.coupang.com/vp/products/1', shortenUrl: 'https://link.coupang.com/a/abc0', landingUrl: undefined },
  ]);
  assert.equal(seen[0].method, 'POST');
  assert.equal(seen[0].query, '');
  assert.deepEqual(JSON.parse(seen[0].body), { coupangUrls: ['https://www.coupang.com/vp/products/1'], subId: 'insta' });
});

test('200인데 rCode 400이면 실패로 처리', async () => {
  mode = 'convert-failed';
  await assert.rejects(client().deeplink(['https://www.coupang.com/vp/products/9']), (e: unknown) => {
    assert.ok(e instanceof CoupangApiError);
    assert.equal(e.kind, 'rejected');
    assert.equal(e.message, 'url convert failed');
    return true;
  });
});

test('401·403·429를 각각 알아듣는 오류로 바꾼다', async () => {
  for (const [m, kind] of [
    ['401', 'auth'],
    ['gateway-403', 'forbidden'],
    ['429', 'rate'],
  ] as const) {
    mode = m;
    await assert.rejects(client().goldbox(), (e: unknown) => e instanceof CoupangApiError && e.kind === kind);
  }
  mode = 'ok';
});

test('틀린 Secret Key는 서명 검증에서 걸린다', async () => {
  mode = 'ok';
  await assert.rejects(
    createClient({ accessKey: ACCESS, secretKey: 'wrong-secret', baseUrl }).goldbox(),
    (e: unknown) => e instanceof CoupangApiError && e.kind === 'auth' && /signature mismatch/.test(e.message),
  );
});

test('beforeCall이 던지면 요청을 보내지 않는다 (호출 한도)', async () => {
  mode = 'ok';
  seen.length = 0;
  const c = client({
    beforeCall: () => {
      throw new CoupangApiError('budget', 'limit');
    },
  });
  await assert.rejects(c.goldbox(), (e: unknown) => e instanceof CoupangApiError && e.kind === 'budget');
  assert.equal(seen.length, 0);
});

test('리포트: 4종을 순서대로, 1000건이면 다음 페이지까지', async () => {
  mode = 'ok';
  seen.length = 0;
  const calls: string[] = [];
  const r = await client({ beforeCall: (ep) => void calls.push(ep) }).reports(
    { startDate: '20261101', endDate: '20261116' },
    'blog',
  );
  assert.equal(r.clicks.length, 1003);
  assert.equal(r.orders.length, 2);
  assert.equal(r.cancels.length, 2);
  assert.equal(r.commission[0].commission, 300);
  assert.deepEqual(calls, [
    '/reports/clicks',
    '/reports/clicks',
    '/reports/orders',
    '/reports/cancels',
    '/reports/commission',
  ]);
  assert.equal(seen[0].query, 'startDate=20261101&endDate=20261116&subId=blog&page=0');
  assert.equal(seen[1].query, 'startDate=20261101&endDate=20261116&subId=blog&page=1');
});

test('네트워크 오류', async () => {
  const c = createClient({ accessKey: ACCESS, secretKey: SECRET, baseUrl: 'http://127.0.0.1:1' });
  await assert.rejects(c.goldbox(), (e: unknown) => e instanceof CoupangApiError && e.kind === 'network');
});

test('키가 비어 있으면 요청 전에 막는다', async () => {
  seen.length = 0;
  await assert.rejects(
    createClient({ accessKey: '', secretKey: '', baseUrl }).goldbox(),
    (e: unknown) => e instanceof CoupangApiError && e.kind === 'auth',
  );
  assert.equal(seen.length, 0);
});
