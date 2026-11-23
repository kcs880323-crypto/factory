/// <reference types="node" />
// 자비스 방식 상품 글·홍보 영상 대본 검사.
import assert from 'node:assert/strict';
import { test } from 'node:test';

import { assemblePost, checkPost, dropBad, josa, parsePost, postPrompt } from '../src/lib/productPost';
import { buildMeta, lintReel, parseReel, reelPrompt, reelScenes } from '../src/lib/productReel';
import { affiliateOf, allowedNumbers, badNumbers, kstToday, type ProductInfo } from '../src/lib/productRules';

const today = kstToday(new Date(Date.UTC(2026, 10, 16, 16))); // 2026-11-17 KST
const item: ProductInfo = {
  name: '초음파 가습기 3L 무드등',
  price: 29800,
  url: 'https://link.coupang.com/a/abc',
  category: '가전디지털',
  rank: 3,
  tag: '카테고리 베스트',
  rocket: true,
  memo: '물통 3L, 타이머 8시간',
  kind: 'shop',
  affiliate: 'coupang',
};
const DISC = '이 포스팅은 쿠팡 파트너스 활동의 일환으로, 이에 따른 일정액의 수수료를 제공받습니다.';

test('제휴 종류는 주소로', () => {
  assert.equal(affiliateOf('https://link.coupang.com/a/x'), 'coupang');
  assert.equal(affiliateOf('https://coupa.ng/abc'), 'coupang');
  assert.equal(affiliateOf('https://www.coupang.com/vp/products/1'), 'none');
  assert.equal(affiliateOf('https://naver.me/abc'), 'naver_connect');
  assert.equal(affiliateOf('nope'), 'none');
});

test('써도 되는 숫자: 상품 정보·가격 표기·순위·오늘 날짜', () => {
  const ok = allowedNumbers(item, today);
  for (const n of ['3', '29800', '2', '9800', '8', '11', '17']) assert.ok(ok.has(n), n);
  assert.deepEqual(badNumbers('2만 9,800원, 3L, 베스트 3위, 11월 17일', ok), []);
  assert.deepEqual(badNumbers('리뷰 1,234개, 할인 35%', ok), ['1234', '35']);
});

test('조사', () => {
  assert.equal(josa('찰스', '이', '가'), '가');
  assert.equal(josa('민준', '이', '가'), '이');
});

test('프롬프트에 규칙·상품 정보·글쓴이 이름이 들어간다', () => {
  const p = postPrompt(item, today, '찰스');
  assert.match(p, /찰스가 직접 써 보거나 다녀온 것이 아니다/);
  assert.match(p, /가격: 29,800원\(11월 17일 기준/);
  assert.match(p, /메모: 물통 3L/);
  assert.match(p, /웹 검색은 하지 않는다/);
  const r = reelPrompt({ ...item, kind: 'travel' }, today);
  assert.match(r, /travel_price/);
  assert.ok(!r.includes('crazy_reverse'), '써 본 척 훅은 자동 영상 메뉴에 없다');
  assert.ok(!r.includes('price_doubt'), '여행이면 생활용품 훅은 빠진다');
});

const goodPost = JSON.stringify({
  title: '초음파 가습기 3L, 2만 9,800원이면 괜찮을까?',
  keyword: '가습기',
  summary: '물통 3L·타이머 8시간 가습기 정보',
  blocks: [
    { type: 'text', role: 'hook', text: '겨울만 되면 아침에 목이 칼칼하잖아요. 오늘은 물통 3L짜리 초음파 가습기 정보를 정리했어요.' },
    { type: 'table', title: '한눈에 보는 상품 정보', rows: [{ k: '가격', v: '29,800원' }, { k: '물통', v: '3L' }, { k: '배송', v: '로켓배송' }] },
    { type: 'heading', text: '💧 이런 분께 딱이에요' },
    { type: 'text', text: '자취방처럼 작은 방에 두기 좋아요. 무드등이 있어 밤에도 은은하답니다.' },
    { type: 'heading', text: '✔ 특징 정리' },
    { type: 'text', text: '타이머가 8시간까지 돼요. 써 보니 조용하더라고요. 리뷰가 1,234개나 된대요!' },
    { type: 'heading', text: '🧡 찰스의 한마디' },
    { type: 'text', role: 'closing', text: '궁금하면 아래에서 확인해 보세요.' },
  ],
  hashtags: ['#가습기', '가습기추천', '#가습기'],
});

test('글 읽기: 블록·해시태그 정리', () => {
  const d = parsePost(`여기 있어요:\n\`\`\`json\n${goodPost}\n\`\`\``);
  assert.equal(d.blocks.length, 8);
  assert.deepEqual(d.hashtags, ['#가습기', '#가습기추천']);
  assert.throws(() => parsePost('죄송해요'), /JSON/);
});

test('글 검사: 없는 숫자·경험담을 잡고, 고쳐도 남으면 그 문장만 뺀다', () => {
  const d = parsePost(goodPost);
  const issues = checkPost(d, item, today);
  const errs = issues.filter((i) => i.severity === 'error');
  assert.ok(errs.some((i) => i.rule === 'product_number' && i.msg.includes('1234') && i.block === 6));
  assert.ok(errs.some((i) => i.rule === 'fake_experience' && i.block === 6));
  assert.equal(errs.filter((i) => i.block !== 6).length, 0, '다른 블록은 깨끗하다');
  assert.ok(issues.some((i) => i.rule === 'body_len' && i.severity === 'warn'));

  const { draft, removed } = dropBad(d, item, today);
  assert.equal(removed.length, 2);
  const six = draft.blocks[5];
  assert.equal(six.type === 'text' && six.text, '타이머가 8시간까지 돼요.');
  assert.equal(checkPost(draft, item, today).filter((i) => i.severity === 'error').length, 0);
});

test('1위·광고 말·주소는 막는다', () => {
  const d = parsePost(
    JSON.stringify({ title: '가습기 판매 1위!', blocks: [{ type: 'text', text: '쿠팡 파트너스 링크 https://x.y 로 사세요. 최저가예요.' }] }),
  );
  const rules = checkPost(d, item, today).map((i) => i.rule);
  assert.ok(rules.includes('claim'));
  assert.ok(rules.includes('ad_words'));
  const one = checkPost(d, { ...item, rank: 1 }, today).filter((i) => i.msg.includes('1위'));
  assert.equal(one.length, 0, '베스트 1위면 1위는 된다');
});

test('완성 글: 광고 고지가 맨 앞, 표 뒤·글 끝에 링크와 가격 기준일, 마지막 안내', () => {
  const { draft } = dropBad(parsePost(goodPost), item, today);
  const f = assemblePost(draft, item, today, { disclosure: DISC, pen: '찰스' });
  assert.ok(f.body.startsWith(`[광고] ${DISC}`));
  const parts = f.body.split('\n\n');
  const tableAt = parts.findIndex((p) => p.startsWith('📋 한눈에 보는 상품 정보'));
  assert.ok(parts[tableAt + 1].startsWith('🔗 상품 보러 가기: https://link.coupang.com/a/abc'));
  assert.match(parts[tableAt + 1], /11월 17일 기준 29,800원/);
  assert.equal(f.body.split('🔗 상품 보러 가기').length - 1, 2);
  assert.match(f.body, /※ 이 글은 2026-11-17 기준 상품 정보로 정리했어요\. 찰스가 직접 써 보거나 다녀온 후기는 아니에요\./);
  assert.ok(f.body.trim().endsWith('#가습기 #가습기추천'));
  const none = assemblePost(draft, { ...item, affiliate: 'none' }, today, { disclosure: DISC, pen: '찰스' });
  assert.ok(none.body.startsWith('이 글은 공개된 상품 정보로 정리한 소개 글이에요(수수료 없음).'));
});

const goodReel = JSON.stringify({
  hook_id: 'number_compare',
  hook: '3L인데\n이 가격?',
  hook_say: '3리터 가습기가 이 가격이에요',
  cuts: [
    { text: '물통이 3L라 넉넉해요', badge: '3L', say: '물통이 넉넉해요' },
    { text: '타이머 8시간', badge: '8시간', say: '자기 전에 맞춰 두면 돼요' },
    { text: '무드등까지 있어요', badge: '', say: '밤에 은은해요' },
    { text: '로켓배송이에요', badge: '베스트 3위', say: '빨리 와요' },
  ],
  title: '3L 가습기가 2만 9,800원?',
  caption: '👇 가습기 고를 때 볼 점\n✅ 물통 3L\n✅ 타이머 8시간',
  hashtags: ['#가습기', '#겨울필수템', '#살림템', '#자취템', '#꿀템', '#여섯번째'],
});

test('대본 검사: 통과하는 대본', () => {
  const sc = parseReel(goodReel);
  const r = lintReel(sc, item, today);
  assert.deepEqual(r.errors, []);
  assert.equal(sc.hook, '3L인데\n이 가격?');
});

test('대본 검사: 경험 말·없는 숫자·직접 찍은 영상 전용 훅·긴 첫 화면은 오류', () => {
  const bad = parseReel(
    JSON.stringify({
      hook_id: 'result_first',
      hook: '요즘 이것만 찾아다녀요 진짜로요',
      cuts: [{ text: '써 보니 조용해요', badge: '리뷰 999개', say: '' }],
      title: '',
      caption: '',
    }),
  );
  const { errors } = lintReel(bad, item, today);
  assert.ok(errors.some((e) => e.includes('첫 화면 문구가 길어요')));
  assert.ok(errors.some((e) => e.includes('컷 수')));
  assert.ok(errors.some((e) => e.includes('999')));
  assert.ok(errors.some((e) => e.includes('써 본 척')));
  assert.ok(errors.some((e) => e.includes('직접 찍은 영상에만')));
});

test('올릴 글: 광고 표시가 첫 줄, 해시태그 5개까지, 끝 안내 방식', () => {
  const sc = parseReel(goodReel);
  const m = buildMeta(sc, item, today, { disclosure: DISC, cta: 'dm' });
  assert.ok(m.title.startsWith('[광고] '));
  assert.ok(m.instagramCaption.startsWith(`[광고] ${DISC}`));
  assert.match(m.instagramCaption, /댓글에 '가격'/);
  assert.equal(m.hashtags.length, 5);
  assert.ok(!m.instagramCaption.includes('https://'), '인스타 캡션 링크는 눌리지 않아 넣지 않는다');
  assert.ok(m.youtubeDescription.split('\n\n')[1].startsWith('🔗 구매 링크: https://link.coupang.com/a/abc'));
  const p = buildMeta(sc, item, today, { disclosure: DISC, cta: 'profile', siteLabel: '찰스의 추천템' });
  assert.match(p.instagramCaption, /프로필 링크\(찰스의 추천템\)/);
  const scenes = reelScenes(sc, 'profile');
  assert.equal(scenes.length, 6);
  assert.equal(scenes.at(-1)!.text, '구매 링크는\n프로필 링크에서');
  assert.equal(Math.round(scenes.reduce((a, s) => a + s.dur, 0) * 10) / 10, 18.8);
});
