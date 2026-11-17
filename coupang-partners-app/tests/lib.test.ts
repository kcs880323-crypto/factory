/// <reference types="node" />
import assert from 'node:assert/strict';
import { test } from 'node:test';

import { createDemoClient, demoReports } from '../src/demo/demoData';
import { composePost, DEFAULT_DISCLOSURE, ensureDisclosureOnTop, hasDisclosureOnTop, type Channel } from '../src/lib/compose';
import { daysBetween, eachDay, kstYmd, presetRange, rangeProblem } from '../src/lib/dates';
import { classifyUrl, extractUrl, isValidSubId, productIdFromUrl } from '../src/lib/links';
import { budget, clampLimit, HOUR } from '../src/lib/rateGuard';
import { summarize } from '../src/lib/reports';

test('KST 날짜: UTC 15:00 이후는 다음 날', () => {
  assert.equal(kstYmd(new Date('2026-11-16T14:59:59Z')), '20261116');
  assert.equal(kstYmd(new Date('2026-11-16T15:00:00Z')), '20261117');
});

test('프리셋 범위는 어제에서 끝나고 30일을 넘지 않는다', () => {
  const now = new Date('2026-11-17T03:00:00Z'); // KST 11/17 12:00
  assert.deepEqual(presetRange('yesterday', now), { startDate: '20261116', endDate: '20261116' });
  assert.deepEqual(presetRange('last7', now), { startDate: '20261110', endDate: '20261116' });
  const r30 = presetRange('last30', now);
  assert.equal(daysBetween(r30.startDate, r30.endDate), 30);
  assert.equal(rangeProblem(r30), null);
  assert.equal(eachDay(presetRange('last7', now)).length, 7);
});

test('범위 검사', () => {
  assert.match(rangeProblem({ startDate: '20261001', endDate: '20261116' }) ?? '', /30일/);
  assert.match(rangeProblem({ startDate: '20261117', endDate: '20261116' }) ?? '', /늦어요/);
  assert.match(rangeProblem({ startDate: '20181001', endDate: '20181010' }) ?? '', /2018/);
});

test('링크 판별', () => {
  assert.equal(classifyUrl('https://link.coupang.com/a/bXyZ12'), 'partner');
  assert.equal(classifyUrl('https://coupa.ng/abc'), 'partner');
  assert.equal(classifyUrl('https://www.coupang.com/vp/products/123?itemId=4'), 'coupang');
  assert.equal(classifyUrl('https://m.coupang.com/vm/products/123'), 'coupang');
  assert.equal(classifyUrl('https://evil.com/link.coupang.com'), 'other');
  assert.equal(classifyUrl('그냥 글자'), 'invalid');
  assert.equal(
    extractUrl('[쿠팡] 상품명 좋네요! https://link.coupang.com/a/abc123). 확인'),
    'https://link.coupang.com/a/abc123',
  );
  assert.equal(productIdFromUrl('https://www.coupang.com/vp/products/7290?x=1'), '7290');
  assert.ok(isValidSubId('blog01'));
  assert.ok(!isValidSubId('블로그'));
});

test('모든 홍보 글은 첫 부분에 대가성 문구가 있다', () => {
  for (const ch of ['blog', 'instagram', 'kakao', 'threads'] as Channel[]) {
    const text = composePost(ch, { title: '무선 이어폰', url: 'https://link.coupang.com/a/x', price: 39900, isRocket: true });
    assert.ok(hasDisclosureOnTop(text), `${ch}: ${text}`);
    assert.ok(text.startsWith('[광고]'), ch);
    if (ch !== 'instagram') assert.ok(text.includes('https://link.coupang.com/a/x'), ch);
    assert.ok(!/\n\n\n/.test(text), `${ch}: 빈 줄이 겹침`);
  }
  const custom = composePost('kakao', { title: 'a', url: 'u', disclosure: '나만의 문구' });
  assert.ok(hasDisclosureOnTop(custom, '나만의 문구'));
});

test('문구를 지우거나 아래로 옮기면 다시 맨 앞에 붙인다', () => {
  const moved = `좋은 상품\n링크\n\n${DEFAULT_DISCLOSURE}`;
  assert.ok(!hasDisclosureOnTop(moved));
  const fixed = ensureDisclosureOnTop(moved);
  assert.ok(hasDisclosureOnTop(fixed));
  assert.equal(fixed.split(DEFAULT_DISCLOSURE).length, 2, '문구는 한 번만');
  assert.equal(ensureDisclosureOnTop(fixed), fixed);
});

test('시간당 호출 한도', () => {
  const now = 10 * HOUR;
  const calls = [now - HOUR - 1, now - 50 * 60000, now - 10 * 60000, now - 1000];
  assert.deepEqual(budget(calls, 5, now), { used: 3, limit: 5, remaining: 2, waitMs: 0 });
  const full = budget(calls, 3, now);
  assert.equal(full.remaining, 0);
  assert.equal(full.waitMs, 10 * 60000); // 가장 오래된 호출(50분 전)이 빠지기까지 10분
  assert.equal(clampLimit(1), 5);
  assert.equal(clampLimit(1000), 200);
  assert.equal(clampLimit(Number.NaN), 30);
});

test('리포트 요약: 주문은 주문번호로 세고, 수익은 수익 리포트를 쓴다', () => {
  const range = { startDate: '20261115', endDate: '20261116' };
  const s = summarize(
    {
      clicks: [
        { date: '20261115', subId: 'blog', click: 100 },
        { date: '20261116', subId: 'insta', click: 50 },
      ],
      orders: [
        { date: '20261115', subId: 'blog', orderId: 1, productId: 9, productName: 'A', quantity: 1, gmv: 10000, commission: 300 },
        { date: '20261115', subId: 'blog', orderId: 1, productId: 8, productName: 'B', quantity: 2, gmv: 4000, commission: 120 },
        { date: '20261116', subId: 'insta', orderId: 2, productId: 9, productName: 'A', quantity: 1, gmv: 10000, commission: 300 },
      ],
      cancels: [{ date: '20261116', orderId: 2, gmv: 10000, commission: 300 }],
      commission: [
        { date: '20261115', subId: 'blog', commission: 420 },
        { date: '20261116', subId: 'insta', commission: 0 },
      ],
    },
    range,
  );
  assert.equal(s.clicks, 150);
  assert.equal(s.orders, 2);
  assert.equal(s.quantity, 4);
  assert.equal(s.gmv, 24000);
  assert.equal(s.cancels, 1);
  assert.equal(s.commission, 420);
  assert.equal(s.conversion, 2 / 150);
  assert.equal(s.days.length, 2);
  assert.deepEqual(s.days[0], { date: '20261115', clicks: 100, orders: 1, commission: 420 });
  assert.equal(s.topProducts[0].name, 'A');
  assert.equal(s.topProducts[0].orders, 2);
  assert.equal(s.channels[0].subId, 'blog');
});

test('수익 리포트가 비면 주문 수수료 - 취소 수수료로 추정', () => {
  const s = summarize(
    {
      clicks: [],
      orders: [{ date: '20261116', orderId: 1, commission: 500, gmv: 1 }],
      cancels: [{ date: '20261116', orderId: 1, commission: 200 }],
      commission: [],
    },
    { startDate: '20261116', endDate: '20261116' },
  );
  assert.equal(s.commission, 300);
  assert.equal(s.conversion, 0);
});

test('예시 데이터는 쿠팡 주소처럼 보이지 않는다', async () => {
  const c = createDemoClient();
  const products = await c.goldbox();
  assert.ok(products.length > 0);
  for (const p of products) {
    assert.equal(classifyUrl(p.productUrl), 'other');
    assert.ok(p.productName.startsWith('[예시]'));
  }
  const links = await c.deeplink(['https://www.coupang.com/vp/products/1']);
  assert.equal(classifyUrl(links[0].shortenUrl), 'other');
  const bundle = demoReports({ startDate: '20261101', endDate: '20261107' });
  const s = summarize(bundle, { startDate: '20261101', endDate: '20261107' });
  assert.ok(s.clicks > 0 && s.orders > 0);
  assert.deepEqual(demoReports({ startDate: '20261101', endDate: '20261107' }), bundle, '같은 범위면 같은 값');
});
