// 붙여 넣은 글에서 주소를 꺼내고, 그 주소가 파트너스 링크인지 판별한다.

export type UrlKind =
  | 'partner' // link.coupang.com/a/... 처럼 수익이 잡히는 파트너스 링크
  | 'coupang' // 일반 쿠팡 상품 주소 (그대로 올리면 수익 없음)
  | 'other'
  | 'invalid';

const PARTNER_HOSTS = ['link.coupang.com', 'coupa.ng'];
const COUPANG_HOSTS = ['coupang.com', 'www.coupang.com', 'm.coupang.com', 'pages.coupang.com'];

/** 공유 문구 섞인 텍스트에서 첫 번째 http(s) 주소만 꺼낸다. */
export function extractUrl(text: string): string {
  const m = text.match(/https?:\/\/[^\s<>"'`]+/i);
  if (!m) return text.trim();
  return m[0].replace(/[).,!?\]}]+$/, '');
}

export function classifyUrl(input: string): UrlKind {
  let u: URL;
  try {
    u = new URL(input.trim());
  } catch {
    return 'invalid';
  }
  if (u.protocol !== 'https:' && u.protocol !== 'http:') return 'invalid';
  const host = u.hostname.toLowerCase();
  if (PARTNER_HOSTS.includes(host)) return 'partner';
  if (COUPANG_HOSTS.includes(host)) return 'coupang';
  return 'other';
}

/** /vp/products/123456 → "123456" */
export function productIdFromUrl(input: string): string | undefined {
  const m = input.match(/\/vp\/products\/(\d+)/);
  return m ? m[1] : undefined;
}

export function productPageUrl(productId: string | number): string {
  return `https://www.coupang.com/vp/products/${productId}`;
}

/** 채널 ID(subId)는 영문·숫자만 쓴다고 보고 검사한다. */
export function isValidSubId(subId: string): boolean {
  return subId === '' || /^[A-Za-z0-9]{1,50}$/.test(subId);
}
