// 쿠팡 파트너스 Open API 요청 서명 (CEA HmacSHA256).
// 서명 대상 메시지 = signed-date + HTTP 메서드 + 경로 + 쿼리 문자열('?' 제외).
// React Native에는 Node의 crypto가 없어서 순수 JS 구현(js-sha256)을 쓴다.
import { sha256 } from 'js-sha256';

export type QueryValue = string | number | boolean | undefined | null;

/** UTC 기준 yyMMddTHHmmssZ (예: 261117T063015Z) */
export function signedDate(now: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return (
    p(now.getUTCFullYear() % 100) +
    p(now.getUTCMonth() + 1) +
    p(now.getUTCDate()) +
    'T' +
    p(now.getUTCHours()) +
    p(now.getUTCMinutes()) +
    p(now.getUTCSeconds()) +
    'Z'
  );
}

/** 빈 값은 빼고, 넣은 순서대로 key=value&... 로 만든다. 서명과 실제 요청에 같은 문자열을 쓴다. */
export function buildQuery(params: Record<string, QueryValue>): string {
  return Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    .join('&');
}

export function hmacHex(secretKey: string, message: string): string {
  return sha256.hmac(secretKey, message);
}

export function authorizationHeader(args: {
  method: string;
  path: string;
  query: string;
  accessKey: string;
  secretKey: string;
  now: Date;
}): string {
  const date = signedDate(args.now);
  const message = date + args.method.toUpperCase() + args.path + args.query;
  const signature = hmacHex(args.secretKey, message);
  return `CEA algorithm=HmacSHA256, access-key=${args.accessKey}, signed-date=${date}, signature=${signature}`;
}
