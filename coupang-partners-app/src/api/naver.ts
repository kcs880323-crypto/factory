// 네이버 API 클라이언트 — 읽기 전용 두 가지.
// 1) API HUB 쇼핑인사이트(데이터랩): 분야별 클릭 추이 (POST /shopping/v1/categories)
//    헤더 X-NCP-APIGW-API-KEY-ID / X-NCP-APIGW-API-KEY
// 2) 네이버 개발자센터 쇼핑 검색: GET /v1/search/shop.json (API HUB에는 없음 — 키가 따로 필요)
//    헤더 X-Naver-Client-Id / X-Naver-Client-Secret
// 네트워크(fetch)를 밖에서 주입받아서 테스트에서는 가짜 서버로 확인한다. 키 값은 오류 글에 싣지 않는다.

export const APIHUB_BASE = 'https://naverapihub.apigw.ntruss.com';
export const OPENAPI_BASE = 'https://openapi.naver.com';
export const TREND_PATH = '/shopping/v1/categories';
export const SHOP_PATH = '/v1/search/shop.json';

export type NaverErrorKind = 'nokey' | 'auth' | 'forbidden' | 'rate' | 'budget' | 'network' | 'http' | 'bad';

export class NaverApiError extends Error {
  kind: NaverErrorKind;
  status?: number;
  constructor(kind: NaverErrorKind, message: string, status?: number) {
    super(message);
    this.name = 'NaverApiError';
    this.kind = kind;
    this.status = status;
  }
}

export interface NaverKeys {
  /** API HUB (쇼핑인사이트) */
  hubId?: string;
  hubSecret?: string;
  /** 개발자센터 '검색' API (쇼핑 검색) */
  shopId?: string;
  shopSecret?: string;
}

export interface NaverClientOptions {
  keys: NaverKeys;
  fetchImpl?: typeof fetch;
  /** 실제 요청 직전. 던지면 보내지 않는다 (호출 한도). */
  beforeCall?: (what: 'trend' | 'shop') => void | Promise<void>;
  /** 웹사이트 중계 주소 (예: "/api/naver"). 주면 네이버 대신 여기로 보내고 목적지는 헤더에 담는다. */
  proxyUrl?: string;
  hubBase?: string;
  openBase?: string;
}

export interface TrendGroup {
  /** 화면 이름 */
  name: string;
  /** 네이버 쇼핑 분야 번호 (예: 50000006 식품) */
  param: string;
}

export interface TrendRow {
  name: string;
  data: { period: string; ratio: number }[];
}

export interface NaverShopItem {
  title: string;
  link: string;
  image: string;
  lprice: string;
  mallName: string;
  productId: string;
  brand: string;
  category1?: string;
  category2?: string;
  category3?: string;
  category4?: string;
}

export interface NaverClient {
  /** 분야별 클릭 추이. 한 번에 3분야까지(네이버 제한). 날짜는 yyyy-mm-dd. */
  categoryTrend(groups: TrendGroup[], startDate: string, endDate: string): Promise<TrendRow[]>;
  shopSearch(query: string, display?: number, sort?: 'sim' | 'date' | 'asc' | 'dsc'): Promise<NaverShopItem[]>;
}

export function hasHub(k: NaverKeys | null | undefined): boolean {
  return !!(k?.hubId && k.hubSecret);
}
export function hasShop(k: NaverKeys | null | undefined): boolean {
  return !!(k?.shopId && k.shopSecret);
}

/** 네이버·NCP 게이트웨이가 돌려주는 여러 모양의 오류 글에서 사람이 읽을 말만 꺼낸다. */
export function naverMessage(json: unknown): string {
  if (!json || typeof json !== 'object') return '';
  const j = json as Record<string, unknown>;
  const err = j.error as Record<string, unknown> | undefined;
  const parts = [j.errorMessage, err?.message, err?.details, j.message].filter((x) => typeof x === 'string' && x.trim());
  return [...new Set(parts as string[])].join(' — ').slice(0, 200);
}

function statusError(status: number, msg: string, what: 'trend' | 'shop'): NaverApiError {
  const name = what === 'trend' ? '네이버 API HUB(쇼핑인사이트)' : '네이버 쇼핑 검색';
  const tail = msg ? ` (네이버: ${msg})` : '';
  if (status === 401) {
    return new NaverApiError(
      'auth',
      what === 'trend'
        ? `${name} 키가 맞지 않거나, 이 Application에서 쇼핑인사이트가 켜져 있지 않아요. API HUB 콘솔에서 Client ID·Secret과 사용 API를 확인해 주세요.${tail}`
        : `${name} 키가 맞지 않아요. 개발자센터 Application의 Client ID·Secret과 '검색' API 사용 설정을 확인해 주세요.${tail}`,
      status,
    );
  }
  if (status === 403) return new NaverApiError('forbidden', `${name}가 요청을 거절했어요. 사용 설정·서비스 환경(웹 도메인 등록)을 확인해 주세요.${tail}`, status);
  if (status === 429) return new NaverApiError('rate', `${name} 하루 호출 한도를 넘었어요. 내일 다시 해 주세요.${tail}`, status);
  return new NaverApiError('http', `${name} 서버가 오류(${status})를 돌려줬어요.${tail}`, status);
}

export function createNaverClient(opts: NaverClientOptions): NaverClient {
  const fetchImpl = opts.fetchImpl ?? fetch;
  const hubBase = (opts.hubBase ?? APIHUB_BASE).replace(/\/$/, '');
  const openBase = (opts.openBase ?? OPENAPI_BASE).replace(/\/$/, '');

  async function call(what: 'trend' | 'shop', path: string, init: { method: 'GET' | 'POST'; headers: Record<string, string>; body?: string }) {
    await opts.beforeCall?.(what);
    const headers = { ...init.headers };
    let url: string;
    if (opts.proxyUrl) {
      url = opts.proxyUrl;
      headers['X-Naver-Target'] = what === 'trend' ? 'hub' : 'open';
      headers['X-Naver-Path'] = path;
    } else {
      url = (what === 'trend' ? hubBase : openBase) + path;
    }
    let res: Response;
    try {
      res = await fetchImpl(url, { method: init.method, headers, body: init.body });
    } catch {
      throw new NaverApiError('network', '인터넷 연결을 확인해 주세요. 네이버 서버에 닿지 못했어요.');
    }
    const text = await res.text();
    let json: unknown = {};
    try {
      json = text ? JSON.parse(text) : {};
    } catch {
      json = {};
    }
    if (!res.ok) {
      if (opts.proxyUrl && (res.status === 404 || res.status === 405) && !naverMessage(json)) {
        throw new NaverApiError('http', '이 웹사이트에 네이버 중계 서버(/api/naver)가 없어요. README의 "웹사이트로 배포하기"대로 Vercel에 올린 주소에서 써 주세요.', res.status);
      }
      throw statusError(res.status, naverMessage(json), what);
    }
    return json as Record<string, unknown>;
  }

  return {
    async categoryTrend(groups, startDate, endDate) {
      if (!hasHub(opts.keys)) throw new NaverApiError('nokey', '네이버 API HUB 키가 없어요. 설정 → 네이버 API에서 Client ID·Secret을 넣어 주세요.');
      const list = groups.slice(0, 3);
      if (!list.length) return [];
      const body = { startDate, endDate, timeUnit: 'date', category: list.map((g) => ({ name: g.name, param: [g.param] })) };
      const json = await call('trend', TREND_PATH, {
        method: 'POST',
        headers: {
          'X-NCP-APIGW-API-KEY-ID': String(opts.keys.hubId).trim(),
          'X-NCP-APIGW-API-KEY': String(opts.keys.hubSecret).trim(),
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(body),
      });
      const results = Array.isArray(json.results) ? (json.results as Record<string, unknown>[]) : [];
      return results.map((r, i) => ({
        name: String(r.title ?? list[i]?.name ?? ''),
        data: (Array.isArray(r.data) ? (r.data as Record<string, unknown>[]) : []).map((d) => ({ period: String(d.period ?? ''), ratio: Number(d.ratio ?? 0) || 0 })),
      }));
    },

    async shopSearch(query, display = 10, sort = 'sim') {
      if (!hasShop(opts.keys)) throw new NaverApiError('nokey', '네이버 쇼핑 검색 키가 없어요. 설정 → 네이버 API에서 개발자센터 Client ID·Secret을 넣어 주세요.');
      const q = query.replace(/\s+/g, ' ').trim().slice(0, 50);
      if (q.length < 2) throw new NaverApiError('bad', '검색어를 두 글자 이상 적어 주세요.');
      const qs = new URLSearchParams({ query: q, display: String(Math.max(1, Math.min(display, 30))), sort }).toString();
      const json = await call('shop', `${SHOP_PATH}?${qs}`, {
        method: 'GET',
        headers: { 'X-Naver-Client-Id': String(opts.keys.shopId).trim(), 'X-Naver-Client-Secret': String(opts.keys.shopSecret).trim() },
      });
      return (Array.isArray(json.items) ? (json.items as NaverShopItem[]) : []).filter((x) => x && typeof x === 'object');
    },
  };
}
