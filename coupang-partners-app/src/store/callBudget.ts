// 기기 전체에서 하나인 API 호출 기록. 요청 직전에 take()로 한도를 확인하고 기록한다.
// React 밖의 순수 코드라서 테스트에서 바로 확인할 수 있다.
import { CoupangApiError } from '../api/client';
import { minutesText } from '../lib/format';
import { budget, recentCalls } from '../lib/rateGuard';

export class CallBudget {
  private calls: number[] = [];
  private listeners = new Set<(calls: number[]) => void>();
  private persist: (calls: number[]) => Promise<void>;
  constructor(persist: (calls: number[]) => Promise<void>) {
    this.persist = persist;
  }

  load(calls: number[], now = Date.now()) {
    this.calls = recentCalls(calls, now);
    this.emit();
  }

  reset() {
    this.calls = [];
    this.emit();
  }

  async take(limit: number, t = Date.now()) {
    const b = budget(this.calls, limit, t);
    if (b.remaining <= 0) {
      throw new CoupangApiError(
        'budget',
        `이번 한 시간 호출 한도(${b.limit}회)를 다 썼어요. ${minutesText(b.waitMs)} 뒤에 다시 해 주세요. 계정 정지를 막기 위한 앱 안의 안전장치예요.`,
      );
    }
    this.calls = [...recentCalls(this.calls, t), t];
    this.emit();
    await this.persist(this.calls);
  }

  subscribe(fn: (calls: number[]) => void) {
    this.listeners.add(fn);
    return () => {
      this.listeners.delete(fn);
    };
  }

  private emit() {
    for (const fn of this.listeners) fn(this.calls);
  }
}
