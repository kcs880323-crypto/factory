// 날짜는 쿠팡 리포트 기준인 한국 시간(KST, UTC+9)으로 계산한다.
import type { DateRange } from '../api/types';

const DAY = 24 * 60 * 60 * 1000;
const KST_OFFSET = 9 * 60 * 60 * 1000;
export const REPORT_MIN_DATE = '20181101';
export const REPORT_MAX_DAYS = 30;

/** KST 기준 날짜를 yyyyMMdd로 */
export function kstYmd(date: Date, shiftDays = 0): string {
  const k = new Date(date.getTime() + KST_OFFSET + shiftDays * DAY);
  return (
    String(k.getUTCFullYear()) +
    String(k.getUTCMonth() + 1).padStart(2, '0') +
    String(k.getUTCDate()).padStart(2, '0')
  );
}

export function parseYmd(ymd: string): Date {
  return new Date(Date.UTC(Number(ymd.slice(0, 4)), Number(ymd.slice(4, 6)) - 1, Number(ymd.slice(6, 8))));
}

export function daysBetween(start: string, end: string): number {
  return Math.round((parseYmd(end).getTime() - parseYmd(start).getTime()) / DAY) + 1;
}

/** yyyyMMdd → 11.17 */
export function shortYmd(ymd: string): string {
  if (!/^\d{8}$/.test(ymd)) return ymd;
  return `${Number(ymd.slice(4, 6))}.${Number(ymd.slice(6, 8))}`;
}

/** yyyyMMdd → 2026.11.17 */
export function longYmd(ymd: string): string {
  if (!/^\d{8}$/.test(ymd)) return ymd;
  return `${ymd.slice(0, 4)}.${ymd.slice(4, 6)}.${ymd.slice(6, 8)}`;
}

export type RangePreset = 'yesterday' | 'last7' | 'last14' | 'last30';

export const RANGE_PRESETS: { key: RangePreset; label: string }[] = [
  { key: 'yesterday', label: '어제' },
  { key: 'last7', label: '최근 7일' },
  { key: 'last14', label: '최근 14일' },
  { key: 'last30', label: '최근 30일' },
];

/** 실적은 매일 오후 3시쯤 전날 것까지 반영되므로, 범위는 어제에서 끝낸다. */
export function presetRange(preset: RangePreset, now: Date): DateRange {
  const end = kstYmd(now, -1);
  const span = { yesterday: 1, last7: 7, last14: 14, last30: 30 }[preset];
  return { startDate: kstYmd(now, -span), endDate: end };
}

/** 범위가 쿠팡 규칙(30일 이내, 2018-11-01 이후, 시작 ≤ 끝)에 맞으면 null, 아니면 이유 */
export function rangeProblem(range: DateRange): string | null {
  const ok = /^\d{8}$/;
  if (!ok.test(range.startDate) || !ok.test(range.endDate)) return '날짜 형식이 올바르지 않아요.';
  if (range.startDate > range.endDate) return '시작일이 종료일보다 늦어요.';
  if (range.startDate < REPORT_MIN_DATE) return '2018년 11월 1일 이후만 조회할 수 있어요.';
  if (daysBetween(range.startDate, range.endDate) > REPORT_MAX_DAYS) return '한 번에 30일까지만 조회할 수 있어요.';
  return null;
}

/** 범위 안의 날짜 목록 (yyyyMMdd) */
export function eachDay(range: DateRange): string[] {
  const out: string[] = [];
  const start = parseYmd(range.startDate).getTime();
  const n = daysBetween(range.startDate, range.endDate);
  for (let i = 0; i < n; i++) {
    const d = new Date(start + i * DAY);
    out.push(
      String(d.getUTCFullYear()) + String(d.getUTCMonth() + 1).padStart(2, '0') + String(d.getUTCDate()).padStart(2, '0'),
    );
  }
  return out;
}
