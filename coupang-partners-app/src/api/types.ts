// 쿠팡 파트너스 Open API 응답 모양. 문서와 실제 응답이 조금씩 달라서
// 숫자 필드는 화면에 쓰기 전에 항상 toNumber()로 한 번 거른다.

export type ImageSize = '230x230' | '300x300' | '512x512';

export interface Product {
  productId: number | string;
  productName: string;
  productPrice: number;
  productImage?: string;
  productUrl: string; // 파트너스 추적이 붙은 링크
  isRocket?: boolean;
  isFreeShipping?: boolean;
  categoryName?: string;
  rank?: number;
  keyword?: string;
}

export interface SearchResult {
  landingUrl?: string; // 검색 결과 페이지로 가는 추적 링크
  products: Product[];
}

export interface DeeplinkItem {
  originalUrl: string;
  shortenUrl: string;
  landingUrl?: string;
}

export interface ClickRow {
  date: string; // yyyyMMdd
  subId?: string;
  click: number;
}

export interface OrderRow {
  date: string;
  subId?: string;
  orderId?: number | string;
  productId?: number | string;
  productName?: string;
  quantity?: number;
  gmv?: number;
  commissionRate?: number;
  commission?: number;
  categoryName?: string;
}

export interface CancelRow extends OrderRow {
  orderDate?: string;
}

export interface CommissionRow {
  date: string;
  subId?: string;
  commission: number;
}

export interface ReportBundle {
  clicks: ClickRow[];
  orders: OrderRow[];
  cancels: CancelRow[];
  commission: CommissionRow[];
}

export interface DateRange {
  startDate: string; // yyyyMMdd
  endDate: string;
}
