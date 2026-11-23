// AI 상품 찾기 — 원하는 제품을 적으면 '같은 상품 중 가장 싼 것'을 골라 파트너스 링크로 보관함에 저장한다.
// 1) 쿠팡 API 키가 있으면: 검색(최대 10개) → 관련 상품만 → 가장 싼 것 → 링크 변환 → 저장 (앱이 혼자 끝까지)
// 2) 없으면: Claude(데스크톱 앱 내장 브라우저·Claude in Chrome)에게 맡길 부탁을 복사 → Claude가 내 브라우저로
//    쿠팡 검색·파트너스 링크 만들기를 하고 마지막 줄에 결과를 적음 → 그 줄을 붙여 넣으면 검사 후 저장.
import type { Product } from '../api/types';
import { productIdFromUrl } from './links';
import { affiliateOf, type Today } from './productRules';

const STOP = new Set(['추천', '최저가', '가장', '제일', '싼', '싸게', '저렴한', '제품', '상품', '쿠팡', '찾아줘', '찾기', '사고', '싶어']);
const ACCESSORY = ['케이스', '커버', '필름', '보호', '거치대', '리필', '부품', '교체용', '호환', '스티커', '파우치', '스트랩', '충전기', '케이블', '중고', '리퍼'];

function compact(s: string): string {
  return s.toLowerCase().replace(/\s+/g, '');
}

/** 검색어 → 꼭 들어가야 할 낱말 (추천·최저가 같은 말은 뺀다). */
export function queryWords(q: string): string[] {
  return q
    .replace(/[^0-9A-Za-z가-힣ㄱ-ㅎㅏ-ㅣ\s]/g, ' ')
    .split(/\s+/)
    .map((w) => w.trim())
    .filter((w) => w && !STOP.has(w));
}

/** 상품 이름에 검색어 낱말이 모두 있고, 검색어에 없던 부속품 말(케이스·필름·리필…)이 없으면 관련 상품. */
export function isRelevant(name: string, q: string): boolean {
  const words = queryWords(q);
  const n = compact(name);
  if (!words.every((w) => n.includes(compact(w)))) return false;
  const qc = compact(q);
  return !ACCESSORY.some((a) => n.includes(a) && !qc.includes(a));
}

export interface CheapestPick {
  chosen: Product | null;
  /** 관련 상품, 싼 순 */
  ranked: Product[];
  /** 관련 상품이 없어서 전체에서 골랐는지 */
  loose: boolean;
  total: number;
}

export function pickCheapest(products: Product[], q: string): CheapestPick {
  const priced = products.filter((p) => p.productPrice > 0 && p.productName);
  const rel = priced.filter((p) => isRelevant(p.productName, q));
  const pool = rel.length ? rel : priced;
  const ranked = [...pool].sort((a, b) => a.productPrice - b.productPrice);
  return { chosen: ranked[0] ?? null, ranked, loose: !rel.length && priced.length > 0, total: products.length };
}

/** 쿠팡 검색 '낮은가격순' 주소 */
export function coupangSearchUrl(q: string): string {
  return `https://www.coupang.com/np/search?q=${encodeURIComponent(q.trim())}&sorter=salePriceAsc`;
}

export const HANDOFF_TAG = '파트너스매니저';

/** Claude에게 맡길 부탁 (Claude가 내 브라우저를 다룬다). 구매·결제는 금지, 로그인은 사람이. */
export function handoffPrompt(q: string, cond: string, today: Today): string {
  const c = cond.trim();
  return `[파트너스 매니저 — AI 상품 찾기 부탁]
내 컴퓨터의 브라우저(Claude 앱의 내장 브라우저, 또는 Claude in Chrome)로 아래를 순서대로 해 줘.

1. 쿠팡에서 「${q.trim()}」을(를) 검색하고 '낮은가격순'으로 정렬해.
   주소: ${coupangSearchUrl(q)}  (정렬이 안 돼 있으면 화면에서 '낮은가격순'을 눌러)
2. 검색어와 정말 같은 상품만 본다 — 부속품(케이스·필름·리필 등), 다른 제품, 중고·리퍼는 뺀다.${c ? `\n   조건: ${c}` : ''}
3. 그중 판매가가 가장 낮은 상품 1개를 골라 상품 페이지를 열고, 화면에 보이는 상품명·판매가·상품 페이지 주소(https://www.coupang.com/vp/products/…)를 적어 둬.
4. 쿠팡 파트너스(https://partners.coupang.com)의 링크 만들기에서 그 상품 페이지 주소로 링크를 만들고, 만들어진 짧은 링크(https://link.coupang.com/a/…)를 받아.
   파트너스에 로그인돼 있지 않으면 멈추고 나에게 로그인해 달라고 말해 줘 (비밀번호·인증번호는 입력하지 마).
5. 장바구니·바로구매·결제 버튼은 절대 누르지 마. 리뷰·문의 쓰기나 계정 설정도 건드리지 마.
6. 상품 정보는 화면에서 직접 본 것만 적고, 모르는 칸은 비워 둬. 지어내지 마.

끝나면 마지막 줄에 아래 형식 한 줄만 적어 줘 (이 줄을 파트너스 매니저에 붙여 넣어 보관함에 저장해):
${HANDOFF_TAG} {"name":"상품명","price":숫자,"link":"https://link.coupang.com/a/…","product_url":"https://www.coupang.com/vp/products/…","rocket":true,"note":"고른 이유 한 줄(화면에서 본 것만)","checked":"${today.iso}"}`;
}

export interface HandoffItem {
  name: string;
  price?: number;
  link: string;
  productUrl?: string;
  productId?: string;
  rocket?: boolean;
  note?: string;
}

/** Claude가 적은 마지막 줄(또는 답 전체)에서 결과를 꺼내 검사한다. 파트너스 링크가 없으면 저장하지 않는다. */
export function parseHandoff(text: string): { item?: HandoffItem; problems: string[] } {
  const t = String(text ?? '');
  const at = t.lastIndexOf(HANDOFF_TAG);
  const from = at >= 0 ? t.slice(at) : t;
  const a = from.indexOf('{');
  const b = from.lastIndexOf('}');
  if (a < 0 || b <= a) return { problems: [`'${HANDOFF_TAG} {…}' 줄을 찾지 못했어요. Claude 답의 마지막 줄을 그대로 붙여 넣어 주세요.`] };
  let d: Record<string, unknown>;
  try {
    d = JSON.parse(from.slice(a, b + 1)) as Record<string, unknown>;
  } catch {
    return { problems: ['결과 줄의 모양이 깨져 있어요. 마지막 줄 전체를 다시 복사해 주세요.'] };
  }
  const problems: string[] = [];
  const name = String(d.name ?? '').replace(/\s+/g, ' ').trim().slice(0, 120);
  if (name.length < 2) problems.push('상품명이 없어요.');
  const link = String(d.link ?? '').trim();
  if (affiliateOf(link) !== 'coupang' || !link.startsWith('https://')) {
    problems.push(link ? `파트너스 링크(https://link.coupang.com/a/…)가 아니에요: ${link.slice(0, 60)}` : '파트너스 링크가 없어요 — 파트너스에 로그인한 뒤 다시 부탁해 주세요.');
  }
  const rawPrice = typeof d.price === 'number' ? d.price : Number(String(d.price ?? '').replace(/[^\d]/g, ''));
  const price = Number.isFinite(rawPrice) && rawPrice > 0 && rawPrice < 100_000_000 ? Math.round(rawPrice) : undefined;
  const pu = String(d.product_url ?? '').trim();
  const productUrl = /^https:\/\/(www\.|m\.)?coupang\.com\/vp\/products\/\d+/.test(pu) ? pu : undefined;
  const note = String(d.note ?? '').replace(/https?:\/\/\S+/g, '').replace(/\s+/g, ' ').trim().slice(0, 120) || undefined;
  if (problems.length) return { problems };
  return {
    item: { name, price, link, productUrl, productId: productUrl ? productIdFromUrl(productUrl) : undefined, rocket: d.rocket === true, note },
    problems: [],
  };
}

/** 보관함 메모 — 언제 어떻게 골랐는지 (이 메모는 글·영상의 사실 근거가 되니 화면에서 본 것만). */
export function aiMemo(q: string, today: Today, how: 'api' | 'claude', note?: string, extra?: string): string {
  const head = `AI 검색 '${q.trim().slice(0, 40)}' (${today.month}/${today.day} 쿠팡 ${how === 'api' ? '검색 결과' : '낮은가격순'}에서 같은 상품 중 가장 싼 것)`;
  return [head, note ? `AI가 화면에서 본 것: ${note}` : '', extra?.trim() ?? ''].filter(Boolean).join(' · ').slice(0, 500);
}
