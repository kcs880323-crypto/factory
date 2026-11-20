// PC판 서버의 'Claude로 상품 찾기' (desktop/claudeAgent.cjs) 부르기. PC 설치판에서만 쓴다.

export interface ClaudeStatus {
  installed: boolean;
  loggedIn: boolean;
  method?: string;
  /** 일부만 보이는 이메일 (예: kcs***@gmail.com) */
  email?: string;
  plan?: string;
  error?: string;
}

export interface ClaudeJob {
  id: string;
  q: string;
  cond: string;
  state: 'running' | 'done' | 'error' | 'stopped' | 'none';
  startedAt: number;
  endedAt?: number;
  steps: { t: number; text: string }[];
  result: string;
  /** Claude 사용량을 달러로 환산한 값 (구독이면 청구액이 아니라 사용량 기준) */
  cost: number | null;
  turns: number | null;
  error: string;
}

export class ClaudeAgentError extends Error {
  code: string;
  constructor(code: string, message: string) {
    super(message);
    this.code = code;
  }
}

async function call<T>(path: string, body?: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`/api/claude/${path}`, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { 'X-PM': '1', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ClaudeAgentError('network', 'PC판 서버에 닿지 못했어요. 바탕화면의 파트너스 매니저로 다시 열어 주세요.');
  }
  const j = (await res.json().catch(() => ({}))) as { code?: string; message?: string };
  if (!res.ok) throw new ClaudeAgentError(j.code || String(res.status), j.message || `서버 오류(${res.status})`);
  return j as T;
}

export const claudeAgent = {
  status: () => call<ClaudeStatus>('status'),
  login: () => call<{ ok: boolean }>('login', {}),
  openBrowser: (target?: 'partners') => call<{ started: boolean }>('browser', { target }),
  search: (q: string, cond: string) => call<ClaudeJob>('search', { q, cond }),
  job: (id: string) => call<ClaudeJob>(`job?id=${encodeURIComponent(id)}`),
  stop: () => call<{ stopped: boolean }>('stop', {}),
};

/** 결과 글에 '파트너스 로그인 필요' / '못 찾음' 표시가 있으면 그 종류 */
export function resultError(text: string): 'partners_login' | 'not_found' | null {
  const m = String(text).match(/"error"\s*:\s*"(partners_login|not_found)"/);
  return m ? (m[1] as 'partners_login' | 'not_found') : null;
}
