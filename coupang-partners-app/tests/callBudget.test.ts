/// <reference types="node" />
import assert from 'node:assert/strict';
import { test } from 'node:test';

import { CoupangApiError, createClient } from '../src/api/client';
import { HOUR } from '../src/lib/rateGuard';
import { CallBudget } from '../src/store/callBudget';

test('한도까지는 기록·저장하고, 넘으면 요청 전에 막는다', async () => {
  const saved: number[][] = [];
  const seen: number[][] = [];
  const b = new CallBudget(async (c) => void saved.push(c));
  b.subscribe((c) => seen.push(c));
  const t0 = 100 * HOUR;
  await b.take(2, t0);
  await b.take(2, t0 + 1000);
  await assert.rejects(b.take(2, t0 + 2000), (e: unknown) => e instanceof CoupangApiError && e.kind === 'budget' && /60분 뒤/.test(e.message));
  assert.equal(saved.length, 2);
  assert.deepEqual(seen.at(-1), [t0, t0 + 1000]);
  // 한 시간이 지나면 다시 가능
  await b.take(2, t0 + HOUR + 1);
  assert.deepEqual(saved.at(-1), [t0 + 1000, t0 + HOUR + 1]);
});

test('저장해 둔 기록을 불러올 때 한 시간 지난 것은 버린다', () => {
  const b = new CallBudget(async () => undefined);
  let last: number[] = [];
  b.subscribe((c) => (last = c));
  const now = 50 * HOUR;
  b.load([now - 2 * HOUR, now - 10, now - 5], now);
  assert.deepEqual(last, [now - 10, now - 5]);
  b.reset();
  assert.deepEqual(last, []);
});

test('클라이언트에 연결하면 한도를 넘는 요청은 네트워크로 나가지 않는다', async () => {
  let fetches = 0;
  const fakeFetch = (async () => {
    fetches++;
    return new Response(JSON.stringify({ rCode: '0', data: [] }), { status: 200 });
  }) as typeof fetch;
  const b = new CallBudget(async () => undefined);
  const c = createClient({ accessKey: 'a'.repeat(12), secretKey: 's'.repeat(12), fetchImpl: fakeFetch, beforeCall: () => b.take(3) });
  await c.goldbox();
  await c.goldbox();
  await c.goldbox();
  await assert.rejects(c.goldbox(), (e: unknown) => e instanceof CoupangApiError && e.kind === 'budget');
  assert.equal(fetches, 3);
});
