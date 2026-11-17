// 앱 안에서 지키는 시간당 API 호출 한도.
// 쿠팡은 짧은 시간에 호출이 몰리면 계정을 24시간 정지하고, 여러 번 반복되면 영구 정지할 수 있다.
// 공개된 수치가 자료마다 달라서(검색 분당 50회, 리포트 시간당 500회, "시간당 약 30회 넘으면 정지" 경험담)
// 가장 보수적인 값을 기본으로 둔다. 설정에서 바꿀 수 있다.

export const HOUR = 60 * 60 * 1000;
export const DEFAULT_HOURLY_LIMIT = 30;
export const MIN_HOURLY_LIMIT = 5;
export const MAX_HOURLY_LIMIT = 200;

export function recentCalls(timestamps: number[], now: number): number[] {
  return timestamps.filter((t) => t > now - HOUR && t <= now + 1000);
}

export interface Budget {
  used: number;
  limit: number;
  remaining: number;
  /** 한도가 다 찼을 때, 한 번 더 부를 수 있게 되기까지 남은 ms */
  waitMs: number;
}

export function budget(timestamps: number[], limit: number, now: number): Budget {
  const recent = recentCalls(timestamps, now).sort((a, b) => a - b);
  const used = recent.length;
  const remaining = Math.max(0, limit - used);
  const waitMs = remaining > 0 ? 0 : Math.max(0, recent[used - limit] + HOUR - now);
  return { used, limit, remaining, waitMs };
}

export function clampLimit(n: number): number {
  if (!Number.isFinite(n)) return DEFAULT_HOURLY_LIMIT;
  return Math.min(MAX_HOURLY_LIMIT, Math.max(MIN_HOURLY_LIMIT, Math.round(n)));
}
