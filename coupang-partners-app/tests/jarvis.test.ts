/// <reference types="node" />
import assert from 'node:assert/strict';
import { test } from 'node:test';

import { composePost, hasDisclosureOnTop } from '../src/lib/compose';
import { JARVIS_CSV_HEAD, linksToCsv, siteProblem, skippedText } from '../src/lib/jarvis';

const base = { createdAt: Date.UTC(2026, 10, 16, 16, 30), source: 'manual' }; // KST 11/17 01:30

test('자비스로 보내기: 머리글이 자비스 형식과 같고, 파트너스 링크만, 예시는 뺀다', () => {
  const r = linksToCsv(
    [
      { ...base, title: '캡형 물티슈 "100매"', url: 'https://link.coupang.com/a/abc123', price: 15900, subId: 'blog01', memo: '뚜껑, 있음' },
      { ...base, title: '접이식 의자', url: 'https://coupa.ng/xyz' },
      { ...base, title: '[예시] 이어폰', url: 'https://example.com/demo-link/1', source: 'demo' },
      { ...base, title: '일반 주소', url: 'https://www.coupang.com/vp/products/1' },
    ],
    { onlyPartner: true },
  );
  const lines = r.csv.split('\n');
  assert.equal(lines[0], JARVIS_CSV_HEAD);
  assert.equal(r.count, 2);
  assert.deepEqual(r.skipped, { demo: 1, notPartner: 1 });
  assert.equal(lines[1], '"캡형 물티슈 ""100매""","https://link.coupang.com/a/abc123","15900","blog01","뚜껑, 있음","2026-11-17"');
  assert.equal(lines[2], '"접이식 의자","https://coupa.ng/xyz","","","","2026-11-17"');
  assert.match(skippedText(r.skipped), /예시 링크 1개.*파트너스 링크가 아닌 것 1개/);
});

test('설정의 CSV 내보내기는 예시만 빼고 모두 넣는다', () => {
  const r = linksToCsv([
    { ...base, title: 'a', url: 'https://www.coupang.com/vp/products/1' },
    { ...base, title: 'b', url: 'https://example.com/demo-link/2', source: 'demo' },
  ]);
  assert.equal(r.count, 1);
  assert.equal(skippedText({ demo: 0, notPartner: 0 }), '');
});

test('인스타 글은 프로필 링크 채널 이름을 넣고, 대가성 문구는 그대로 맨 앞', () => {
  const t = composePost('instagram', { title: '무선 이어폰', url: 'https://link.coupang.com/a/x', profileLabel: '찰스의 추천템' });
  assert.ok(t.includes('프로필 링크(찰스의 추천템)에서'));
  assert.ok(hasDisclosureOnTop(t));
  assert.ok(composePost('instagram', { title: 'x', url: 'u' }).includes('🔗 구매 링크는 프로필 링크에서 확인하세요'));
});

test('내 채널 주소 검사', () => {
  assert.equal(siteProblem('찰스의 추천템', 'https://kcs880323-crypto.github.io/factory/docs/'), null);
  assert.match(siteProblem('블로그', 'http://blog.naver.com/x') ?? '', /https/);
  assert.match(siteProblem('', 'https://a.b') ?? '', /이름/);
  assert.match(siteProblem('a', 'blog') ?? '', /주소/);
});
