/// <reference types="node" />
import assert from 'node:assert/strict';
import { test } from 'node:test';

import type { Product } from '../src/api/types';
import { aiMemo, coupangSearchUrl, handoffPrompt, HANDOFF_TAG, isRelevant, parseHandoff, pickCheapest, queryWords } from '../src/lib/aiSearch';
import { kstToday } from '../src/lib/productRules';

const today = kstToday(new Date(Date.UTC(2026, 10, 16, 16)));
const P = (id: number, name: string, price: number): Product => ({ productId: id, productName: name, productPrice: price, productUrl: `https://link.coupang.com/re/${id}` });

test('검색어 낱말: 추천·최저가 같은 말은 뺀다', () => {
  assert.deepEqual(queryWords('캡형 물티슈 100매 최저가 추천!'), ['캡형', '물티슈', '100매']);
});

test('관련 상품: 낱말이 모두 있고, 검색어에 없던 부속품 말이 없어야', () => {
  assert.ok(isRelevant('[1+1] 캡형 물티슈 100매 x 10팩', '물티슈 100매'));
  assert.ok(!isRelevant('물티슈 케이스 100매용', '물티슈 100매'));
  assert.ok(isRelevant('아이폰 16 케이스 투명', '아이폰 16 케이스'));
  assert.ok(!isRelevant('물티슈 80매', '물티슈 100매'));
});

test('가장 싼 것: 관련 상품 중에서, 없으면 전체에서(loose)', () => {
  const list = [P(1, '캡형 물티슈 100매 10팩', 12900), P(2, '물티슈 케이스 100매', 3000), P(3, '물티슈 100매 20팩', 21000), P(4, '물티슈 100매', 0)];
  const r = pickCheapest(list, '물티슈 100매');
  assert.equal(r.chosen?.productId, 1);
  assert.equal(r.ranked.length, 2);
  assert.equal(r.loose, false);
  const l = pickCheapest(list, '가습기');
  assert.equal(l.loose, true);
  assert.equal(l.chosen?.productId, 2);
  assert.equal(pickCheapest([], '가습기').chosen, null);
});

test('맡길 부탁: 낮은가격순 주소·금지 사항·결과 줄 형식', () => {
  const p = handoffPrompt('물티슈 100매', '로켓배송만', today);
  assert.ok(p.includes(coupangSearchUrl('물티슈 100매')));
  assert.match(p, /sorter=salePriceAsc/);
  assert.match(p, /조건: 로켓배송만/);
  assert.match(p, /결제 버튼은 절대 누르지 마/);
  assert.match(p, /비밀번호·인증번호는 입력하지 마/);
  assert.ok(p.trim().split('\n').at(-1)!.startsWith(`${HANDOFF_TAG} {`));
  assert.match(p, /"checked":"2026-11-17"/);
});

test('결과 줄 읽기: 파트너스 링크·가격·상품 주소', () => {
  const ans = `다 했어요!\n고른 상품은 ...\n${HANDOFF_TAG} {"name":"캡형 물티슈 100매 10팩","price":"12,900","link":"https://link.coupang.com/a/abc123","product_url":"https://www.coupang.com/vp/products/987654?itemId=1","rocket":true,"note":"로켓배송, 낮은가격순 첫 상품 https://x.y","checked":"2026-11-17"}`;
  const r = parseHandoff(ans);
  assert.deepEqual(r.problems, []);
  assert.equal(r.item?.price, 12900);
  assert.equal(r.item?.productId, '987654');
  assert.equal(r.item?.rocket, true);
  assert.equal(r.item?.note, '로켓배송, 낮은가격순 첫 상품');
});

test('결과 줄 읽기: 파트너스 링크가 아니면 저장하지 않는다', () => {
  assert.match(parseHandoff(`${HANDOFF_TAG} {"name":"물티슈","link":"https://www.coupang.com/vp/products/1"}`).problems[0], /파트너스 링크/);
  assert.match(parseHandoff(`${HANDOFF_TAG} {"name":"물티슈","link":""}`).problems[0], /로그인한 뒤/);
  assert.match(parseHandoff('그냥 글').problems[0], /찾지 못했어요/);
  assert.match(parseHandoff(`${HANDOFF_TAG} {"name": 물티슈}`).problems[0], /깨져/);
  const noPrice = parseHandoff(`${HANDOFF_TAG} {"name":"물티슈","price":"모름","link":"https://link.coupang.com/a/x"}`);
  assert.equal(noPrice.item?.price, undefined);
});

test('메모', () => {
  assert.equal(aiMemo('물티슈', today, 'api'), "AI 검색 '물티슈' (11/17 쿠팡 검색 결과에서 같은 상품 중 가장 싼 것)");
  assert.match(aiMemo('물티슈', today, 'claude', '로켓배송', '아이 있는 집용'), /낮은가격순.*AI가 화면에서 본 것: 로켓배송 · 아이 있는 집용/);
});
