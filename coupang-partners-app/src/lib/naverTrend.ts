// 네이버 상품 추천 — 자비스 🛒 '가장 핫한 물품'(shop_sources.hot)과 같은 셈법.
// 점수 = 팔리는 신호(쿠팡 골드박스·베스트 순위, 없으면 네이버 검색 상위) 45 + 뜨는 분야(네이버 쇼핑인사이트 최근 7일 ÷ 앞 21일) 25
//      + 이달에 많이 찾는 말 15 + 영상으로 보여 주기 좋음(가격·사진, 용량·개수 숫자) 15.
// 판매량·화제성을 직접 잰 값이 아니다 — 화면에도 그렇게 적는다.
import type { Product } from '../api/types';
import type { NaverShopItem } from '../api/naver';

export interface TrendCategory {
  key: string;
  name: string;
  /** 쿠팡 카테고리 베스트 번호 */
  coupang: number[];
  /** 네이버 쇼핑 분야 번호(쇼핑인사이트) */
  naver: string;
  /** 쿠팡 키가 없을 때 네이버 쇼핑 검색에 쓸 씨앗 말 */
  seed: string[];
}

export const TREND_CATEGORIES: TrendCategory[] = [
  { key: 'kitchen', name: '주방·식품', coupang: [1012, 1013], naver: '50000006', seed: ['주방 꿀템', '간편식'] },
  { key: 'living', name: '청소·생활', coupang: [1014], naver: '50000008', seed: ['청소 꿀템', '생활용품 추천'] },
  { key: 'appliance', name: '가전', coupang: [1016], naver: '50000003', seed: ['소형가전 추천'] },
  { key: 'baby', name: '육아', coupang: [1011], naver: '50000005', seed: ['육아 꿀템'] },
  { key: 'beauty', name: '뷰티·건강', coupang: [1010, 1024], naver: '50000002', seed: ['스킨케어 추천'] },
  { key: 'home', name: '인테리어·수납', coupang: [1015], naver: '50000004', seed: ['수납 정리템'] },
  // 여행: 쿠팡 국내·해외여행, 네이버는 '여가/생활편의' 분야(여행만 따로 재지는 않는다)
  { key: 'travel', name: '여행·여가', coupang: [1025, 1026], naver: '50000009', seed: ['여행 준비물', '여행 꿀템'] },
];

export const CATEGORY_BY_KEY: Record<string, TrendCategory> = Object.fromEntries(TREND_CATEGORIES.map((c) => [c.key, c]));

/** 달마다 사람들이 찾는 것 — 사실 주장이 아니라 고르는 눈(자비스와 같은 표). */
export const SEASON: Record<number, string[]> = {
  1: ['가습기', '전기요', '새해 다이어트', '설 선물'],
  2: ['설 선물', '신학기', '미세먼지'],
  3: ['신학기', '봄 청소', '미세먼지'],
  4: ['봄 나들이', '캠핑', '미세먼지'],
  5: ['어버이날 선물', '캠핑', '선풍기'],
  6: ['선풍기', '제습기', '여름 휴가'],
  7: ['제습기', '휴가', '냉감'],
  8: ['휴가', '선풍기', '신학기'],
  9: ['추석 선물', '환절기'],
  10: ['환절기', '가습기', '할로윈'],
  11: ['김장', '가습기', '전기요', '블랙프라이데이'],
  12: ['크리스마스 선물', '연말', '핫팩', '가습기'],
};

const DAY = 24 * 60 * 60 * 1000;
const KST = 9 * 60 * 60 * 1000;

function kstDate(d: Date, shift = 0): string {
  const k = new Date(d.getTime() + KST + shift * DAY);
  return `${k.getUTCFullYear()}-${String(k.getUTCMonth() + 1).padStart(2, '0')}-${String(k.getUTCDate()).padStart(2, '0')}`;
}

export function kstMonth(d: Date): number {
  return new Date(d.getTime() + KST).getUTCMonth() + 1;
}

/** 쇼핑인사이트 기간: 어제까지 29일 (자비스와 같음). */
export function trendWindow(now: Date): { startDate: string; endDate: string } {
  return { startDate: kstDate(now, -29), endDate: kstDate(now, -1) };
}

/** 비율 목록 → 최근 7일 평균 ÷ 그 앞 21일 평균 (1.0 = 그대로, 1.3 = 30% 오름). 자료가 모자라면 null. */
export function momentum(values: (number | null | undefined)[]): number | null {
  const vals = values.filter((v): v is number => typeof v === 'number' && Number.isFinite(v));
  if (vals.length < 14) return null;
  const last = vals.slice(-7);
  const prev = vals.slice(-28, -7);
  const base = prev.length ? prev.reduce((a, b) => a + b, 0) / prev.length : 0;
  if (base <= 0) return null;
  return Math.round(((last.reduce((a, b) => a + b, 0) / last.length) / base) * 1000) / 1000;
}

export function momentumText(m: number | null | undefined): string {
  if (m === null || m === undefined) return '자료 부족';
  const p = Math.round((m - 1) * 100);
  return p === 0 ? '그대로' : p > 0 ? `${p}% 오름` : `${-p}% 내림`;
}

export interface RecItem {
  source: 'coupang' | 'naver';
  id: string;
  name: string;
  price: number;
  image?: string;
  url: string;
  category?: string;
  rank?: number;
  /** 어디서 찾았나: 오늘의 특가(골드박스) / 카테고리 베스트 / 네이버 쇼핑 검색 */
  tag: string;
  isRocket?: boolean;
  isFreeShipping?: boolean;
  mall?: string;
  /** 우리 분야 key */
  cat?: string;
  /** 쿠팡 API 결과는 파트너스 추적 링크, 네이버 검색 결과는 수수료 링크가 아니다 */
  affiliate: 'coupang' | 'none';
}

export interface ScoredItem extends RecItem {
  score: number;
  why: string[];
}

export function fromCoupang(p: Product, tag: string, cat?: string): RecItem {
  return {
    source: 'coupang',
    id: String(p.productId ?? ''),
    name: p.productName,
    price: p.productPrice || 0,
    image: p.productImage,
    url: p.productUrl,
    category: p.categoryName,
    rank: p.rank && p.rank > 0 ? p.rank : undefined,
    tag,
    isRocket: p.isRocket,
    isFreeShipping: p.isFreeShipping,
    cat,
    affiliate: 'coupang',
  };
}

const ENTITIES: Record<string, string> = { '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&#39;': "'", '&apos;': "'" };

export function cleanNaverText(text: unknown, limit = 120): string {
  return String(text ?? '')
    .replace(/<\/?b>/g, '')
    .replace(/&(amp|lt|gt|quot|#39|apos);/g, (m) => ENTITIES[m] ?? m)
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, limit);
}

export function fromNaver(it: NaverShopItem, cat?: string): RecItem | null {
  const name = cleanNaverText(it?.title);
  if (!name) return null;
  const price = Number(it.lprice) || 0;
  const cats = [it.category1, it.category2, it.category3, it.category4].map((c) => cleanNaverText(c, 30)).filter(Boolean);
  const link = String(it.link ?? '');
  return {
    source: 'naver',
    id: String(it.productId ?? ''),
    name,
    price: price > 0 ? price : 0,
    image: it.image ? String(it.image) : undefined,
    url: link.startsWith('https://') ? link : '',
    category: cats.join(' > ') || undefined,
    tag: '네이버 쇼핑 검색',
    mall: cleanNaverText(it.mallName, 40) || undefined,
    cat,
    affiliate: 'none',
  };
}

const NUMBER_HINT = /\d+\s*(?:kg|g|ml|L|l|매|개|입|구|단|W|인치|cm|mm)/;

/** (점수 0~100, 이유). 자비스 shop_sources.score와 같은 셈. */
export function scoreItem(item: RecItem, trend: number | null | undefined, month: number): { score: number; why: string[] } {
  const why: string[] = [];
  let pts = 0;
  if (item.tag.startsWith('오늘의 특가')) {
    pts += 38;
    why.push('쿠팡 골드박스(오늘의 특가)');
  } else if (item.rank) {
    const r = Math.max(1, Math.floor(item.rank));
    pts += 45 * Math.max(0, 1 - (r - 1) / 20);
    why.push(`쿠팡 ${item.category || '카테고리'} 베스트 ${r}위`);
  } else if (item.source === 'naver') {
    pts += 20;
    why.push('네이버 쇼핑 검색 상위');
  }
  if (trend) {
    pts += Math.max(0, Math.min(25, (trend - 1) * 100));
    if (trend > 1.03) why.push(`네이버 분야 클릭 최근 7일 ${Math.round((trend - 1) * 100)}% 오름`);
  }
  const words = SEASON[month] ?? [];
  const compact = item.name.replace(/\s/g, '');
  const hit = words.find((w) => compact.includes(w.replace(/\s/g, '')));
  if (hit) {
    pts += 15;
    why.push(`${month}월에 많이 찾는 '${hit}'`);
  }
  if (item.price && item.image) pts += 8;
  if (NUMBER_HINT.test(item.name)) {
    pts += 7;
    why.push('숫자로 보여 줄 거리(용량·개수·크기)가 있음');
  }
  return { score: Math.round(Math.min(100, pts)), why };
}

/** 같은 상품을 하나로, 점수순, 최대 40개. */
export function rankItems(items: RecItem[], trend: Record<string, number | null>, month: number, max = 40): ScoredItem[] {
  const seen = new Set<string>();
  const out: ScoredItem[] = [];
  for (const it of items) {
    if (!it.name) continue;
    const k = `${it.source}:${it.id || it.name}`;
    if (seen.has(k)) continue;
    seen.add(k);
    const s = scoreItem(it, it.cat ? trend[it.cat] : null, month);
    out.push({ ...it, score: s.score, why: s.why });
  }
  out.sort((a, b) => b.score - a.score);
  return out.slice(0, max);
}

export interface RecommendSources {
  /** 분야별 쇼핑인사이트 (3개씩 나눠 부른다) */
  trend?: (cats: TrendCategory[]) => Promise<Record<string, number | null>>;
  goldbox?: () => Promise<Product[]>;
  best?: (categoryId: number) => Promise<Product[]>;
  naverShop?: (query: string) => Promise<NaverShopItem[]>;
}

export interface RecommendResult {
  items: ScoredItem[];
  trend: Record<string, number | null>;
  notes: string[];
  used: string[];
  month: number;
  at: number;
}

/** 자비스 hot()과 같은 순서: 뜨는 분야 → 쿠팡(골드박스+베스트) → 쿠팡 없으면 네이버 쇼핑 검색. 키가 없는 길은 건너뛰고 notes에 적는다. */
export async function recommend(src: RecommendSources, keys: string[], now: Date, errorText: (e: unknown) => string): Promise<RecommendResult> {
  const cats = keys.map((k) => CATEGORY_BY_KEY[k]).filter(Boolean);
  const list = cats.length ? cats : TREND_CATEGORIES;
  const month = kstMonth(now);
  const notes: string[] = [];
  const used: string[] = [];
  let trend: Record<string, number | null> = {};
  if (src.trend) {
    try {
      trend = await src.trend(list);
      used.push('네이버 데이터랩 쇼핑인사이트(분야 클릭 추이)');
    } catch (e) {
      notes.push(`네이버 분야 추이를 못 읽었어요: ${errorText(e)}`);
    }
  } else {
    notes.push("네이버 API HUB 키가 없어 '뜨는 분야' 점수는 빼고 셌어요.");
  }
  const items: RecItem[] = [];
  if (src.goldbox || src.best) {
    if (src.goldbox) {
      try {
        items.push(...(await src.goldbox()).slice(0, 15).map((p) => fromCoupang(p, '오늘의 특가(골드박스)')));
        used.push('쿠팡 골드박스');
      } catch (e) {
        notes.push(`쿠팡 골드박스를 못 읽었어요: ${errorText(e)}`);
      }
    }
    if (src.best) {
      let anyBest = false;
      outer: for (const c of list) {
        for (const id of c.coupang) {
          try {
            items.push(...(await src.best(id)).map((p) => fromCoupang(p, '카테고리 베스트', c.key)));
            anyBest = true;
          } catch (e) {
            notes.push(`쿠팡 ${c.name} 베스트를 못 읽었어요: ${errorText(e)}`);
            // 호출 한도에 걸렸으면 더 부르지 않는다
            if (/한도/.test(errorText(e))) break outer;
          }
        }
      }
      if (anyBest) used.push('쿠팡 카테고리 베스트');
    }
  } else if (src.naverShop) {
    notes.push('쿠팡 파트너스 키가 없어 네이버 쇼핑 검색(이달의 말·분야 씨앗 말)으로만 찾았어요 — 판매 순위는 없고, 네이버 링크는 수수료 링크가 아니에요.');
    let anyShop = false;
    outer2: for (const c of list) {
      for (const q of [...(SEASON[month] ?? []).slice(0, 1), ...c.seed.slice(0, 1)]) {
        try {
          items.push(...(await src.naverShop(q)).map((it) => fromNaver(it, c.key)).filter((x): x is RecItem => !!x));
          anyShop = true;
        } catch (e) {
          notes.push(errorText(e));
          break outer2;
        }
      }
    }
    if (anyShop) used.push('네이버 쇼핑 검색');
  } else {
    notes.push('상품을 찾을 키가 아직 없어요 — 쿠팡 파트너스 키(또는 네이버 쇼핑 검색 키)를 넣으면 상품 후보가 나와요. 지금은 뜨는 분야와 이달의 말만 보여 줘요.');
  }
  return { items: rankItems(items, trend, month), trend, notes, used, month, at: now.getTime() };
}
