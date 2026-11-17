// 자비스 🛒 상품 글·홍보 영상이 함께 쓰는 규칙(자비스 shop_reel.py·blog_hotissue.py와 같은 선).
// - 사실은 [상품 정보]에 있는 것만: 숫자는 상품 정보(이름·가격·순위·카테고리·메모·날짜)에 나온 숫자만.
// - 써 보지 않았다: 경험담('써 보니·다녀와 보니·내돈내산·우리 집…') 금지.
// - 효능·치료 단정, 근거 없는 '최저가'·'1위'(베스트 1위일 때만), 연예인·방송 이름 금지.
// - 광고 표시·수수료 문구·구매 링크·가격 기준일은 코드가 붙인다 (모델이 쓰지 않는다).

export type Affiliate = 'coupang' | 'naver_connect' | 'none';
export type ProductKind = 'shop' | 'travel';

export interface ProductInfo {
  name: string;
  price: number;
  url: string;
  category?: string;
  rank?: number;
  /** 어디서 찾았나 (예: 카테고리 베스트) */
  tag?: string;
  rocket?: boolean;
  freeShip?: boolean;
  /** 글쓴이가 상품 페이지에서 확인해 적은 메모 — 사실 근거 */
  memo?: string;
  kind: ProductKind;
  affiliate: Affiliate;
}

export const EXPERIENCE = new RegExp(
  '다녀와\\s*보니|다녀왔|가\\s*봤|묵어\\s*보니|묵어\\s*봤|자\\s*봤|써\\s*보니|써\\s*봤|사용해\\s*보니|사용해\\s*봤|먹어\\s*보니|먹어\\s*봤|발라\\s*보니|' +
    '직접\\s*(?:써|사용|먹|발라|해\\s*보)|내\\s*돈\\s*내\\s*산|제가\\s*(?:쓰|써|먹|샀)|저희\\s*집|우리\\s*집|솔직\\s*후기|찐\\s*후기',
);
export const CLAIMS = /최저가|완치|치료|효능|효과\s*보장|부작용\s*(?:없|0)|100\s*%|무조건|평생|의사\s*추천|식약처\s*인증/;
export const CELEB = /님\s*(?:믿고|이\s*쓰|께서|추천|픽)|방송에\s*나온|연예인|유튜버\s*\S+|셀럽|TV\s*나온|홈쇼핑\s*완판/;
export const NUM = /\d[\d,.]*/g;

const PARTNER_HOSTS = ['link.coupang.com', 'coupa.ng'];

/** 링크 주소로 제휴 종류를 정한다. 일반 쿠팡 주소·그 밖의 주소는 수수료 링크로 보지 않는다. */
export function affiliateOf(url: string): Affiliate {
  let host = '';
  try {
    host = new URL(url).hostname.toLowerCase();
  } catch {
    return 'none';
  }
  if (PARTNER_HOSTS.includes(host)) return 'coupang';
  if (host === 'naver.me' || host.endsWith('.naver.com') || host === 'naver.com') return 'naver_connect';
  return 'none';
}

export function cleanNumber(n: string): string {
  return n.replace(/,/g, '').replace(/\.+$/, '');
}

export function numbersIn(text: string): string[] {
  return (text.match(NUM) ?? []).map(cleanNumber).filter(Boolean);
}

export interface Today {
  month: number;
  day: number;
  /** yyyy-mm-dd */
  iso: string;
}

export function kstToday(now = new Date()): Today {
  const k = new Date(now.getTime() + 9 * 60 * 60 * 1000);
  const month = k.getUTCMonth() + 1;
  const day = k.getUTCDate();
  return { month, day, iso: `${k.getUTCFullYear()}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}` };
}

/** 글·대본에 써도 되는 숫자: 상품 정보에 나온 숫자 + 가격의 여러 표기 + 순위 + 오늘 날짜. (자비스 allowed_numbers) */
export function allowedNumbers(item: ProductInfo, today: Today): Set<string> {
  const nums = new Set<string>();
  for (const text of [item.name, item.category ?? '', item.memo ?? '', item.tag ?? '']) for (const n of numbersIn(text)) nums.add(n);
  const p = Math.round(item.price || 0);
  if (p > 0) {
    nums.add(String(p));
    nums.add(p >= 10000 ? String(Math.floor(p / 10000)) : String(p));
    nums.add(String(Math.round((p / 10000) * 10) / 10).replace(/\.0$/, ''));
    if (p >= 10000) {
      // '2만 9,800원'처럼 나눠 쓴 것
      nums.add(String(Math.floor(p / 10000)));
      nums.add(String(p % 10000));
    }
  }
  if (item.rank) nums.add(String(item.rank));
  nums.add(String(today.month));
  nums.add(String(today.day));
  nums.delete('');
  return nums;
}

const SMALL = new Set(Array.from({ length: 11 }, (_, i) => String(i)));

/** 상품 정보에 없는 숫자 (0~10은 '두 가지'처럼 흔해서 넘긴다). */
export function badNumbers(text: string, okn: Set<string>): string[] {
  return [...new Set(numbersIn(text))].filter((n) => !okn.has(n) && !SMALL.has(n)).sort();
}

export function wonText(n: number): string {
  return `${Math.round(n).toLocaleString('ko-KR')}원`;
}

/** 모델에게 줄 [상품 정보] (자비스 product_facts / facts_text). */
export function factsText(item: ProductInfo, today: Today): string {
  const rows = [`- 상품 이름: ${item.name}`];
  rows.push(item.price ? `- 가격: ${wonText(item.price)}(${today.month}월 ${today.day}일 기준 — 바뀔 수 있음)` : '- 가격: (모름)');
  if (item.category) rows.push(`- 분류: ${item.category}`);
  if (item.rank) rows.push(`- ${item.tag || '베스트'} 순위: ${item.rank}위`);
  else if (item.tag) rows.push(`- 찾은 곳: ${item.tag}`);
  if (item.rocket) rows.push('- 로켓배송');
  if (item.freeShip) rows.push('- 무료배송');
  if (item.memo) rows.push(`- 글쓴이가 상품 페이지에서 확인해 적은 메모: ${item.memo}`);
  return rows.join('\n');
}

/** 광고 고지 (글·캡션 맨 위). 쿠팡은 설정의 대가성 문구를 그대로 쓴다. */
export function adNotice(item: ProductInfo, coupangDisclosure: string): string {
  if (item.affiliate === 'coupang') return `[광고] ${coupangDisclosure}`;
  if (item.affiliate === 'naver_connect') return '[광고] 이 게시물은 네이버 쇼핑 커넥트 활동으로, 판매 시 일정액의 수수료를 받을 수 있습니다.';
  return '이 글은 공개된 상품 정보로 정리한 소개 글이에요(수수료 없음).';
}

/** 모델 답에서 JSON 한 덩어리를 꺼낸다 (앞뒤 설명·코드블록이 붙어도). */
export function extractJson(text: string): Record<string, unknown> {
  const t = String(text ?? '');
  const a = t.indexOf('{');
  const b = t.lastIndexOf('}');
  if (a < 0 || b <= a) throw new Error('답에서 JSON을 찾지 못했어요. 답 전체를 그대로 붙여 넣었는지 확인해 주세요.');
  let data: unknown;
  try {
    data = JSON.parse(t.slice(a, b + 1));
  } catch {
    throw new Error('JSON 모양이 깨져 있어요. 답 전체를 다시 붙여 넣거나, 한 번 더 써 달라고 해 주세요.');
  }
  if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('답의 모양이 달라요.');
  return data as Record<string, unknown>;
}

/** 문장 나누기 (마침표·물음표·느낌표 뒤 공백, 또는 줄바꿈). */
export function sentences(text: string): string[] {
  return text
    .replace(/([.!?。…~])\s+/g, '$1\n')
    .split(/\n+/)
    .map((s) => s.trim())
    .filter(Boolean);
}

/** 금지 말을 찾는다: [규칙 이름, 걸린 말] */
export function forbiddenHits(text: string, opts: { experience: boolean }): { rule: string; label: string; hit: string }[] {
  const out: { rule: string; label: string; hit: string }[] = [];
  const checks: [RegExp, string, string, boolean][] = [
    [EXPERIENCE, 'fake_experience', '써 본 척·다녀온 척하는 말', opts.experience],
    [CLAIMS, 'claim', '근거 없는 단정·효능 말', true],
    [CELEB, 'celeb', '연예인·방송·다른 사람 이름을 빌린 말', true],
  ];
  for (const [rx, rule, label, on] of checks) {
    if (!on) continue;
    const m = text.match(rx);
    if (m) out.push({ rule, label, hit: m[0] });
  }
  return out;
}
