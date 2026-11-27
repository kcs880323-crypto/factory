/// <reference types="node" />
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { test } from 'node:test';

import { authorizationHeader, buildQuery, hmacHex, signedDate } from '../src/api/signature';

test('signed-date는 UTC yyMMddTHHmmssZ', () => {
  assert.equal(signedDate(new Date('2026-11-17T06:30:15.987Z')), '261117T063015Z');
  assert.equal(signedDate(new Date('2030-01-02T03:04:05Z')), '300102T030405Z');
});

test('js-sha256 HMAC이 Node crypto와 같다 (한글·특수문자 포함)', () => {
  const cases: [string, string][] = [
    ['secret', 'hello'],
    ['a'.repeat(100), '261117T063015ZGET/v2/providers/affiliate_open_api/apis/openapi/v1/products/search'],
    ['키-비밀', 'keyword=%EB%AC%BC%ED%8B%B0%EC%8A%88&limit=10'],
    ['', '빈 키'],
  ];
  for (const [key, msg] of cases) {
    assert.equal(hmacHex(key, msg), createHmac('sha256', key).update(msg).digest('hex'));
  }
});

test('쿼리는 빈 값을 빼고 순서를 지키며 UTF-8 퍼센트 인코딩', () => {
  assert.equal(
    buildQuery({ keyword: '물티슈 캡형', limit: 10, subId: undefined, imageSize: '' }),
    'keyword=%EB%AC%BC%ED%8B%B0%EC%8A%88%20%EC%BA%A1%ED%98%95&limit=10',
  );
  assert.equal(buildQuery({}), '');
});

test('Authorization 헤더 = 문서의 참조 구현과 같은 값', () => {
  const now = new Date('2026-11-17T06:30:15Z');
  const path = '/v2/providers/affiliate_open_api/apis/openapi/v1/products/search';
  const query = 'keyword=%EB%85%B8%ED%8A%B8%EB%B6%81&limit=10';
  const header = authorizationHeader({ method: 'get', path, query, accessKey: 'AK', secretKey: 'SK', now });
  const expectedSig = createHmac('sha256', 'SK')
    .update('261117T063015Z' + 'GET' + path + query)
    .digest('hex');
  assert.equal(header, `CEA algorithm=HmacSHA256, access-key=AK, signed-date=261117T063015Z, signature=${expectedSig}`);
});
