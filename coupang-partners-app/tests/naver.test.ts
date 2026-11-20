/// <reference types="node" />
// 네이버 클라이언트를 가짜 네이버 서버에 붙여 본다: 바로 부르기 + 웹사이트 중계(api/naver.js) 거치기.
// 키 헤더가 그대로 가는지, 허용하지 않은 주소는 중계가 막는지, 오류 글에 키 값이 실리지 않는지 확인한다.
import assert from 'node:assert/strict';
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';
import { after, before, test } from 'node:test';

import { createNaverClient, NaverApiError, SHOP_PATH, TREND_PATH } from '../src/api/naver';
import { momentum, recommend, scoreItem, trendWindow, type RecItem } from '../src/lib/naverTrend';

const HUB_ID = 'hub-id-0001';
const HUB_SECRET = 'hub-secret-0001';
const SHOP_ID = 'shop-id-0001';
const SHOP_SECRET = 'shop-secret-0001';

let naver: Server;
let site: Server;
let naverUrl = '';
let siteUrl = '';
const seen: { method: string; url: string; body: string; headers: IncomingMessage['headers'] }[] = [];

const listen = (s: Server) => new Promise<string>((r) => s.listen(0, '127.0.0.1', () => r(`http://127.0.0.1:${(s.address() as AddressInfo).port}`)));

function series(n: number, early: number, late: number) {
  return Array.from({ length: n }, (_, i) => ({ period: `2026-10-${String(i + 1).padStart(2, '0')}`, ratio: i >= n - 7 ? late : early }));
}

before(async () => {
  naver = createServer((req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      seen.push({ method: String(req.method), url: String(req.url), body, headers: req.headers });
      res.setHeader('Content-Type', 'application/json');
      const url = String(req.url);
      if (url === TREND_PATH) {
        if (req.headers['x-ncp-apigw-api-key-id'] !== HUB_ID || req.headers['x-ncp-apigw-api-key'] !== HUB_SECRET) {
          res.statusCode = 401;
          return res.end(JSON.stringify({ error: { errorCode: '200', message: 'Authentication Failed', details: 'Invalid authentication information.' } }));
        }
        const b = JSON.parse(body) as { category: { name: string; param: string[] }[] };
        return res.end(
          JSON.stringify({
            results: b.category.map((c, i) => ({ title: c.name, category: c.param, data: series(28, 10, i === 0 ? 13 : 9) })),
          }),
        );
      }
      if (url.startsWith(`${SHOP_PATH}?`)) {
        if (req.headers['x-naver-client-id'] !== SHOP_ID || req.headers['x-naver-client-secret'] !== SHOP_SECRET) {
          res.statusCode = 401;
          return res.end(JSON.stringify({ errorMessage: 'Not Exist Client ID : Authentication failed. (인증에 실패했습니다.)', errorCode: '024' }));
        }
        return res.end(
          JSON.stringify({
            items: [
              { title: '<b>가습기</b> 초음파 3L &amp; 무드등', link: 'https://search.shopping.naver.com/catalog/1', image: 'https://shopping-phinf.pstatic.net/a.jpg', lprice: '29800', mallName: '네이버', productId: '111', brand: 'B', category1: '디지털/가전', category2: '계절가전' },
            ],
          }),
        );
      }
      res.statusCode = 404;
      res.end('{}');
    });
  });
  naverUrl = await listen(naver);
  process.env.NAVER_APIHUB_BASE = naverUrl;
  process.env.NAVER_OPENAPI_BASE = naverUrl;
  const mod = (await import('../api/naver.js')) as { default: (req: IncomingMessage, res: ServerResponse) => Promise<void> };
  site = createServer((req, res) => {
    if (req.url === '/api/naver') return void mod.default(req, res);
    res.statusCode = 404;
    res.end('<html>not found</html>');
  });
  siteUrl = await listen(site);
});

after(() => {
  site.close();
  naver.close();
});

const keys = { hubId: HUB_ID, hubSecret: HUB_SECRET, shopId: SHOP_ID, shopSecret: SHOP_SECRET };

test('쇼핑인사이트: 바로 부르면 키 헤더·본문이 네이버 형식 그대로 간다', async () => {
  const c = createNaverClient({ keys, hubBase: naverUrl, openBase: naverUrl });
  const rows = await c.categoryTrend(
    [
      { name: '식품', param: '50000006' },
      { name: '생활', param: '50000008' },
      { name: '가전', param: '50000003' },
      { name: '넷째는 잘림', param: '50000004' },
    ],
    '2026-10-19',
    '2026-11-16',
  );
  assert.equal(rows.length, 3);
  const last = seen.at(-1)!;
  assert.equal(last.method, 'POST');
  const body = JSON.parse(last.body);
  assert.equal(body.timeUnit, 'date');
  assert.deepEqual(body.category[0], { name: '식품', param: ['50000006'] });
  assert.equal(body.category.length, 3);
  assert.equal(momentum(rows[0].data.map((d) => d.ratio)), 1.3);
});

test('쇼핑 검색: 바로 부르기, <b> 태그는 점수 단계에서 걷어 낸다', async () => {
  const c = createNaverClient({ keys, hubBase: naverUrl, openBase: naverUrl });
  const items = await c.shopSearch('가습기 추천', 5);
  assert.equal(items.length, 1);
  const q = new URL(naverUrl + seen.at(-1)!.url).searchParams;
  assert.equal(q.get('query'), '가습기 추천');
  assert.equal(q.get('display'), '5');
});

test('웹사이트 중계를 거쳐도 같은 결과, 중계는 키를 네이버로 그대로 넘긴다', async () => {
  const c = createNaverClient({ keys, proxyUrl: `${siteUrl}/api/naver` });
  const rows = await c.categoryTrend([{ name: '식품', param: '50000006' }], '2026-10-19', '2026-11-16');
  assert.equal(rows[0].data.length, 28);
  assert.equal(seen.at(-1)!.url, TREND_PATH);
  assert.equal(seen.at(-1)!.headers['x-ncp-apigw-api-key-id'], HUB_ID);
  const items = await c.shopSearch('가습기');
  assert.equal(items[0].productId, '111');
});

test('중계는 허용한 두 주소 말고는 막는다', async () => {
  const bad = [
    { target: 'hub', path: '/search/v1/blog?query=x', method: 'GET' },
    { target: 'open', path: '/v1/search/blog.json?query=x', method: 'GET' },
    { target: 'hub', path: '/shopping/v1/../v1/categories', method: 'POST' },
    { target: 'open', path: `${SHOP_PATH}?q=1`, method: 'POST' },
    { target: 'evil', path: TREND_PATH, method: 'POST' },
  ];
  for (const b of bad) {
    const r = await fetch(`${siteUrl}/api/naver`, {
      method: b.method,
      headers: { 'X-Naver-Target': b.target, 'X-Naver-Path': b.path, 'X-NCP-APIGW-API-KEY-ID': 'a', 'X-NCP-APIGW-API-KEY': 'b', 'X-Naver-Client-Id': 'a', 'X-Naver-Client-Secret': 'b' },
      body: b.method === 'POST' ? '{}' : undefined,
    });
    assert.equal(r.status, 400, `${b.target} ${b.path}`);
  }
  const noKey = await fetch(`${siteUrl}/api/naver`, { method: 'POST', headers: { 'X-Naver-Target': 'hub', 'X-Naver-Path': TREND_PATH }, body: '{}' });
  assert.equal(noKey.status, 401);
});

test('키가 틀리면 알아들을 수 있는 말, 오류 글에 키 값은 없다', async () => {
  const c = createNaverClient({ keys: { hubId: HUB_ID, hubSecret: 'wrong-secret-xyz', shopId: SHOP_ID, shopSecret: 'wrong-shop-xyz' }, hubBase: naverUrl, openBase: naverUrl });
  await assert.rejects(c.categoryTrend([{ name: '식품', param: '50000006' }], 'a', 'b'), (e: unknown) => {
    assert.ok(e instanceof NaverApiError);
    assert.equal(e.kind, 'auth');
    assert.match(e.message, /쇼핑인사이트/);
    assert.match(e.message, /Authentication Failed/);
    assert.ok(!e.message.includes('wrong-secret-xyz'));
    return true;
  });
  await assert.rejects(c.shopSearch('가습기'), (e: unknown) => e instanceof NaverApiError && e.kind === 'auth' && /인증에 실패/.test(e.message));
  const none = createNaverClient({ keys: {} });
  await assert.rejects(none.categoryTrend([{ name: '식품', param: '1' }], 'a', 'b'), (e: unknown) => e instanceof NaverApiError && e.kind === 'nokey');
});

test('중계가 없는 웹사이트(404 HTML)에서는 배포 안내', async () => {
  const c = createNaverClient({ keys, proxyUrl: `${siteUrl}/nope` });
  await assert.rejects(c.shopSearch('가습기'), (e: unknown) => e instanceof NaverApiError && /중계 서버/.test(e.message));
});

test('호출 한도 확인(beforeCall)이 던지면 보내지 않는다', async () => {
  const n = seen.length;
  const c = createNaverClient({
    keys,
    hubBase: naverUrl,
    beforeCall: () => {
      throw new NaverApiError('budget', '한도');
    },
  });
  await assert.rejects(c.categoryTrend([{ name: '식품', param: '1' }], 'a', 'b'), (e: unknown) => e instanceof NaverApiError && e.kind === 'budget');
  assert.equal(seen.length, n);
});

test('기간은 어제까지 29일(한국 시간)', () => {
  // 2026-11-17 01:00 KST = 2026-11-16 16:00 UTC
  assert.deepEqual(trendWindow(new Date(Date.UTC(2026, 10, 16, 16))), { startDate: '2026-10-19', endDate: '2026-11-16' });
});

test('momentum: 14일 미만이면 모른다, 앞이 0이면 모른다', () => {
  assert.equal(momentum([1, 2, 3]), null);
  assert.equal(momentum(Array(28).fill(0)), null);
  assert.equal(momentum(Array(28).fill(5)), 1);
});

test('점수는 자비스와 같은 셈', () => {
  const base: RecItem = { source: 'coupang', id: '1', name: '초음파 가습기 3L', price: 29800, image: 'x', url: 'https://link.coupang.com/a/1', tag: '카테고리 베스트', rank: 1, affiliate: 'coupang', category: '가전디지털' };
  const s = scoreItem(base, 1.3, 11);
  // 베스트 1위 45 + 추이 25(상한) + 11월 '가습기' 15 + 가격·사진 8 + 용량 숫자 7 = 100
  assert.equal(s.score, 100);
  assert.ok(s.why.some((w) => w.includes('베스트 1위')));
  assert.ok(s.why.some((w) => w.includes('30% 오름')));
  const gold = scoreItem({ ...base, rank: undefined, tag: '오늘의 특가(골드박스)', name: '물티슈', image: undefined }, null, 6);
  assert.equal(gold.score, 38);
  const nv = scoreItem({ ...base, source: 'naver', rank: undefined, tag: '네이버 쇼핑 검색', name: '무드등', affiliate: 'none' }, 0.9, 6);
  assert.equal(nv.score, 28);
});

test('recommend: 키 없는 길은 건너뛰고 이유를 적는다, 쿠팡 없으면 네이버 검색', async () => {
  const c = createNaverClient({ keys, hubBase: naverUrl, openBase: naverUrl });
  const now = new Date(Date.UTC(2026, 10, 16, 16));
  const r = await recommend(
    {
      trend: async (cats) => {
        const rows = await c.categoryTrend(cats.slice(0, 3).map((x) => ({ name: x.name, param: x.naver })), 'a', 'b');
        return Object.fromEntries(cats.slice(0, 3).map((x, i) => [x.key, momentum(rows[i].data.map((d) => d.ratio))]));
      },
      naverShop: (q) => c.shopSearch(q, 5),
    },
    ['appliance', 'kitchen'],
    now,
    (e) => (e instanceof Error ? e.message : String(e)),
  );
  assert.equal(r.month, 11);
  assert.ok(r.used.includes('네이버 쇼핑 검색'));
  assert.ok(r.notes.some((n) => n.includes('수수료 링크가 아니에요')));
  assert.equal(r.items.length, 1, '같은 상품은 하나로');
  assert.equal(r.items[0].name, '가습기 초음파 3L & 무드등');
  assert.equal(r.items[0].affiliate, 'none');
  assert.ok(r.items[0].why.some((w) => w.includes("'가습기'")));

  const empty = await recommend({}, [], now, String);
  assert.equal(empty.items.length, 0);
  assert.equal(empty.notes.length, 2);
});
