import type { DateRange } from '../api/types';
import type { ImprovementCategory, ImprovementEntry, ImprovementStatus } from '../data/improvements';

export const CATEGORY_LABEL: Record<ImprovementCategory, string> = {
  reels: '제품 릴스',
  page: '추천 페이지',
  guard: '품질 가드',
  experiment: 'A/B 실험',
};

export const STATUS_LABEL: Record<ImprovementStatus, string> = {
  done: '조치 완료',
  in_progress: '진행 중',
};

/** '2026-11-24' -> '11월 24일' */
export function formatMonthDay(ymd: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(ymd.trim());
  if (!m) return ymd;
  return `${Number(m[2])}월 ${Number(m[3])}일`;
}

/** '2026-11-24' -> '20261124' */
export function toCompactYmd(ymd: string): string {
  return ymd.replaceAll('-', '');
}

/** 개선 날짜가 조회 기간(YYYYMMDD..YYYYMMDD) 안에 들어가는지 확인 */
export function isInDateRange(ymd: string, range: DateRange): boolean {
  const compact = toCompactYmd(ymd);
  return compact >= range.startDate && compact <= range.endDate;
}

export type ImprovementFilter = 'all' | 'done' | 'in_progress';

/** 최신 날짜순으로 정렬하고 상태 필터를 적용한다. */
export function selectImprovements(
  items: readonly ImprovementEntry[],
  filter: ImprovementFilter = 'all',
): ImprovementEntry[] {
  return items
    .filter((item) => (filter === 'all' ? true : item.status === filter))
    .slice()
    .sort((a, b) => b.date.localeCompare(a.date));
}
