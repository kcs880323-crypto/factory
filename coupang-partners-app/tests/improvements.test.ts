/// <reference types="node" />
import assert from 'node:assert/strict';
import { test } from 'node:test';

import { IMPROVEMENTS } from '../src/data/improvements';
import { formatMonthDay, isInDateRange, selectImprovements } from '../src/lib/improvements';

test('날짜를 몇월 몇일 형식으로 변환한다', () => {
  assert.equal(formatMonthDay('2026-11-24'), '11월 24일');
  assert.equal(formatMonthDay('2026-01-05'), '1월 5일');
  assert.equal(formatMonthDay('invalid'), 'invalid');
});

test('개선 이력 날짜가 조회 기간 안에 있는지 판별한다', () => {
  const range = { startDate: '20261118', endDate: '20261124' };
  assert.equal(isInDateRange('2026-11-24', range), true);
  assert.equal(isInDateRange('2026-11-18', range), true);
  assert.equal(isInDateRange('2026-11-17', range), false);
});

test('상태 필터와 최신순 정렬이 동작하고 등록된 항목 형식이 유효하다', () => {
  assert.ok(IMPROVEMENTS.length >= 1);
  for (const item of IMPROVEMENTS) {
    assert.match(item.date, /^\d{4}-\d{2}-\d{2}$/);
    assert.ok(item.id.length > 0);
    assert.ok(item.title.length > 0);
    assert.ok(item.summary.length > 0);
  }
  const doneOnly = selectImprovements(IMPROVEMENTS, 'done');
  assert.ok(doneOnly.every((x) => x.status === 'done'));
  const inProgressOnly = selectImprovements(IMPROVEMENTS, 'in_progress');
  assert.ok(inProgressOnly.every((x) => x.status === 'in_progress'));
});
