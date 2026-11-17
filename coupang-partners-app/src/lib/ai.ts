// 글·대본을 Claude에게 맡기는 길.
// - Claude 아티팩트 화면: sample 기능(보는 사람의 Claude 사용량, 처음 한 번 허락을 묻는다).
// - 휴대폰 앱·웹사이트: 앱 안에 AI가 없다 → 프롬프트를 복사해 Claude 앱에 붙여 넣고, 받은 답을 다시 붙여 넣는다.
import { HOSTING } from './hosting';

export interface SampleOptions {
  onText?: (p: { text: string; delta: string }) => void;
  signal?: AbortSignal;
  modelTier?: 'quick' | 'default' | 'complex';
  cache?: boolean;
}
export type Sampler = (input: string, opts?: SampleOptions) => Promise<{ text: string; truncated?: boolean }>;

interface ClaudeRuntime {
  use: (name: string) => Promise<unknown>;
}

let pending: Promise<Sampler | null> | null = null;

/** Claude 화면이면 sample 함수, 아니면 null (한 번만 묻고 기억). */
export function getSampler(): Promise<Sampler | null> {
  if (HOSTING !== 'artifact') return Promise.resolve(null);
  if (!pending) {
    const c = (globalThis as { claude?: ClaudeRuntime }).claude;
    pending = c?.use
      ? c
          .use('sample')
          .then((s) => (typeof s === 'function' ? (s as Sampler) : null))
          .catch(() => null)
      : Promise.resolve(null);
  }
  return pending;
}

export function sampleErrorText(e: unknown): string {
  const code = (e as { code?: string } | null)?.code;
  switch (code) {
    case 'not_granted':
      return 'Claude 사용을 허락하지 않아서 쓸 수 없어요. 프롬프트 복사 방식으로 써 주세요.';
    case 'rate_limited':
    case 'queue_overflow':
      return 'Claude 요청이 몰려 있어요. 잠시 뒤에 다시 눌러 주세요.';
    case 'cancelled':
      return '멈췄어요.';
    case 'prompt_too_large':
      return '보낼 글이 너무 길어요. 메모를 줄여 주세요.';
    case 'refused':
      return 'Claude가 이 요청을 거절했어요. 상품 정보·메모를 확인해 주세요.';
    case 'session_expired':
      return '로그인이 끝났어요. 페이지를 새로 고친 뒤 다시 해 주세요.';
    case 'sampling_disabled':
    case 'not_declared':
    case 'capability_disabled':
    case 'capability_removed':
      return '이 화면에서 Claude를 부를 수 없어요. 프롬프트 복사 방식으로 써 주세요.';
    default:
      return (e as { message?: string } | null)?.message || 'Claude 답을 받지 못했어요. 잠시 뒤에 다시 해 주세요.';
  }
}
