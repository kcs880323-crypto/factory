// 쿠팡 파트너스 Open API 클라이언트.
// 네트워크(fetch)와 시계(now)를 밖에서 주입받아서, 테스트에서는 가짜 게이트웨이로 확인한다.
import { authorizationHeader, buildQuery, type QueryValue } from './signature';
import type {
  CancelRow,
  ClickRow,
  CommissionRow,
  DateRange,
  DeeplinkItem,
  ImageSize,
  OrderRow,
  Product,
  ReportBundle,
  SearchResult,
} from './types';

export const BASE_URL = 'https://api-gateway.coupang.com';
export const API_PATH = '/v2/providers/affiliate_open_api/apis/openapi/v1';
export const REPORT_PAGE_SIZE = 1000;
/** 리포트 한 종류당 최대 페이지 수. 호출 한도를 지키려고 일부러 작게 둔다. */
export const MAX_REPORT_PAGES = 5;

export type ApiErrorKind =
  | 'auth' // 401: 키가 틀렸거나 휴대폰 시계가 어긋남
  | 'forbidden' // 403: API 미승인 또는 일시 정지
  | 'rate' // 429: 쿠팡이 호출을 막음
  | 'rejected' // 200인데 rCode가 0이 아님 (예: url convert failed)
  | 'budget' // 앱의 시간당 호출 한도에 걸림 (요청을 보내지 않음)
  | 'network'
  | 'http';

export class CoupangApiError extends Error {
  kind: ApiErrorKind;
  status?: number;
  constructor(kind: ApiErrorKind, message: string, status?: number) {
    super(message);
    this.name = 'CoupangApiError';
    this.kind = kind;
    this.status = status;
  }
}

export interface ClientOptions {
  accessKey: string;
  secretKey: string;
  baseUrl?: string;
  fetchImpl?: typeof fetch;
  now?: () => Date;
  /** 실제 요청을 보내기 직전에 불린다. 던지면 요청을 보내지 않는다 (호출 한도 관리용). */
  beforeCall?: (endpoint: string) => void | Promise<void>;
  /**
   * 웹사이트용 중계 주소 (예: "/api/coupang"). 주면 쿠팡 대신 여기로 보내고, 쿠팡 경로는
   * X-Coupang-Path 헤더에 담는다. 서명은 쿠팡 경로 기준 그대로라서 중계는 손대지 않고 전달만 한다.
   */
  proxyUrl?: string;
}

export interface ProductOptions {
  limit?: number;
  subId?: string;
  imageSize?: ImageSize;
}

export interface ApiClient {
  searchProducts(keyword: string, opts?: ProductOptions): Promise<SearchResult>;
  goldbox(opts?: ProductOptions): Promise<Product[]>;
  bestCategory(categoryId: number, opts?: ProductOptions): Promise<Product[]>;
  coupangPL(opts?: ProductOptions): Promise<Product[]>;
  deeplink(urls: string[], subId?: string): Promise<DeeplinkItem[]>;
  reports(range: DateRange, subId?: string): Promise<ReportBundle>;
}

export function toNumber(v: unknown): number {
  if (typeof v === 'number') return Number.isFinite(v) ? v : 0;
  if (typeof v === 'string') {
    const n = Number(v.replace(/,/g, ''));
    return Number.isFinite(n) ? n : 0;
  }
  return 0;
}

function asArray(v: unknown): Record<string, unknown>[] {
  return Array.isArray(v) ? (v as Record<string, unknown>[]) : [];
}

export function normalizeProduct(raw: Record<string, unknown>): Product {
  return {
    productId: (raw.productId as number | string) ?? '',
    productName: String(raw.productName ?? ''),
    productPrice: toNumber(raw.productPrice),
    productImage: raw.productImage ? String(raw.productImage) : undefined,
    productUrl: String(raw.productUrl ?? ''),
    isRocket: Boolean(raw.isRocket),
    isFreeShipping: Boolean(raw.isFreeShipping),
    categoryName: raw.categoryName ? String(raw.categoryName) : undefined,
    rank: raw.rank === undefined ? undefined : toNumber(raw.rank),
    keyword: raw.keyword ? String(raw.keyword) : undefined,
  };
}

function statusError(status: number, apiMessage: string): CoupangApiError {
  if (status === 401) {
    return new CoupangApiError(
      'auth',
      apiMessage || '키가 맞지 않아요. Access Key·Secret Key를 다시 확인하고, 휴대폰 시간이 자동으로 맞춰져 있는지도 확인하세요.',
      status,
    );
  }
  if (status === 403) {
    return new CoupangApiError(
      'forbidden',
      apiMessage || '쿠팡이 요청을 거절했어요. API 사용 승인이 났는지, 계정이 일시 정지 상태가 아닌지 파트너스 사이트에서 확인하세요.',
      status,
    );
  }
  if (status === 429) {
    return new CoupangApiError(
      'rate',
      '쿠팡의 호출 한도를 넘었어요. 바로 다시 시도하지 말고 한 시간 이상 쉬세요. 반복되면 계정이 정지될 수 있어요.',
      status,
    );
  }
  return new CoupangApiError('http', apiMessage || `쿠팡 서버가 오류(${status})를 돌려줬어요.`, status);
}

export function createClient(opts: ClientOptions): ApiClient {
  const baseUrl = (opts.baseUrl ?? BASE_URL).replace(/\/$/, '');
  const fetchImpl = opts.fetchImpl ?? fetch;
  const now = opts.now ?? (() => new Date());

  async function request(
    method: 'GET' | 'POST',
    endpoint: string,
    params: Record<string, QueryValue> = {},
    body?: unknown,
  ): Promise<unknown> {
    if (!opts.accessKey || !opts.secretKey) {
      throw new CoupangApiError('auth', 'API 키가 없어요. 설정 탭에서 Access Key와 Secret Key를 넣어 주세요.');
    }
    await opts.beforeCall?.(endpoint);

    const path = API_PATH + endpoint;
    const query = buildQuery(params);
    const target = path + (query ? `?${query}` : '');
    const url = opts.proxyUrl ?? baseUrl + target;
    const headers: Record<string, string> = {
      Authorization: authorizationHeader({
        method,
        path,
        query,
        accessKey: opts.accessKey,
        secretKey: opts.secretKey,
        now: now(),
      }),
      'Content-Type': 'application/json;charset=UTF-8',
    };
    if (opts.proxyUrl) headers['X-Coupang-Path'] = target;

    let res: Response;
    try {
      res = await fetchImpl(url, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch {
      throw new CoupangApiError('network', '인터넷 연결을 확인해 주세요. 쿠팡 서버에 닿지 못했어요.');
    }

    const text = await res.text();
    let json: Record<string, unknown> = {};
    try {
      json = text ? (JSON.parse(text) as Record<string, unknown>) : {};
    } catch {
      json = {};
    }
    const apiMessage = String(json.rMessage ?? json.message ?? '').trim();

    if (!res.ok) {
      if (opts.proxyUrl && (res.status === 404 || res.status === 405) && !json.rCode) {
        throw new CoupangApiError(
          'http',
          '이 웹사이트에 API 중계 서버(/api/coupang)가 없어요. README의 "웹사이트로 배포하기"대로 Vercel에 올린 주소에서 써 주세요.',
          res.status,
        );
      }
      throw statusError(res.status, apiMessage);
    }

    // 200이어도 실패일 수 있다 (예: 판매 종료 상품의 딥링크 → rCode 400 "url convert failed").
    const rCode = json.rCode;
    if (rCode !== undefined && rCode !== null && String(rCode) !== '0') {
      throw new CoupangApiError('rejected', apiMessage || `쿠팡이 요청을 처리하지 못했어요 (코드 ${String(rCode)}).`);
    }
    return json.data;
  }

  async function allPages(endpoint: string, range: DateRange, subId?: string) {
    const rows: Record<string, unknown>[] = [];
    for (let page = 0; page < MAX_REPORT_PAGES; page++) {
      const data = asArray(
        await request('GET', endpoint, {
          startDate: range.startDate,
          endDate: range.endDate,
          subId,
          page,
        }),
      );
      rows.push(...data);
      if (data.length < REPORT_PAGE_SIZE) break;
    }
    return rows;
  }

  return {
    async searchProducts(keyword, o = {}) {
      const data = (await request('GET', '/products/search', {
        keyword: keyword.trim(),
        limit: Math.min(o.limit ?? 10, 10),
        subId: o.subId,
        imageSize: o.imageSize,
      })) as Record<string, unknown> | undefined;
      return {
        landingUrl: data?.landingUrl ? String(data.landingUrl) : undefined,
        products: asArray(data?.productData).map(normalizeProduct),
      };
    },

    async goldbox(o = {}) {
      const data = await request('GET', '/products/goldbox', { subId: o.subId, imageSize: o.imageSize });
      return asArray(data).map(normalizeProduct);
    },

    async bestCategory(categoryId, o = {}) {
      const data = await request('GET', `/products/bestcategories/${categoryId}`, {
        limit: Math.min(o.limit ?? 20, 100),
        subId: o.subId,
        imageSize: o.imageSize,
      });
      return asArray(data).map(normalizeProduct);
    },

    async coupangPL(o = {}) {
      const data = await request('GET', '/products/coupangPL', {
        limit: Math.min(o.limit ?? 20, 100),
        subId: o.subId,
        imageSize: o.imageSize,
      });
      return asArray(data).map(normalizeProduct);
    },

    async deeplink(urls, subId) {
      const coupangUrls = urls.map((u) => u.trim()).filter(Boolean);
      if (coupangUrls.length === 0) throw new CoupangApiError('rejected', '변환할 쿠팡 주소가 없어요.');
      const data = await request('POST', '/deeplink', {}, subId ? { coupangUrls, subId } : { coupangUrls });
      return asArray(data).map((d) => ({
        originalUrl: String(d.originalUrl ?? ''),
        shortenUrl: String(d.shortenUrl ?? ''),
        landingUrl: d.landingUrl ? String(d.landingUrl) : undefined,
      }));
    },

    async reports(range, subId) {
      // 리포트 4종을 차례로 부른다 (동시에 몰아서 부르지 않음).
      const clicks = await allPages('/reports/clicks', range, subId);
      const orders = await allPages('/reports/orders', range, subId);
      const cancels = await allPages('/reports/cancels', range, subId);
      const commission = await allPages('/reports/commission', range, subId);
      return {
        clicks: clicks.map(
          (r): ClickRow => ({ date: String(r.date ?? ''), subId: r.subId ? String(r.subId) : undefined, click: toNumber(r.click) }),
        ),
        orders: orders.map(toOrderRow),
        cancels: cancels.map((r): CancelRow => ({ ...toOrderRow(r), orderDate: r.orderDate ? String(r.orderDate) : undefined })),
        commission: commission.map(
          (r): CommissionRow => ({
            date: String(r.date ?? ''),
            subId: r.subId ? String(r.subId) : undefined,
            commission: toNumber(r.commission),
          }),
        ),
      };
    },
  };
}

function toOrderRow(r: Record<string, unknown>): OrderRow {
  return {
    date: String(r.date ?? ''),
    subId: r.subId ? String(r.subId) : undefined,
    orderId: r.orderId as number | string | undefined,
    productId: r.productId as number | string | undefined,
    productName: r.productName ? String(r.productName) : undefined,
    quantity: toNumber(r.quantity),
    gmv: toNumber(r.gmv),
    commissionRate: toNumber(r.commissionRate),
    commission: toNumber(r.commission),
    categoryName: r.categoryName ? String(r.categoryName) : undefined,
  };
}
