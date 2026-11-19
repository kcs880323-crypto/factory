// "예시 데이터" 모드: API 키 없이 화면을 둘러볼 수 있게 하는 가짜 클라이언트.
// 실제 쿠팡 상품·실적이 아니다. 화면마다 "예시"라고 표시하고, 링크도 쿠팡 주소처럼 보이지 않게 만든다.
import type { ApiClient } from '../api/client';
import type { DateRange, OrderRow, Product, ReportBundle } from '../api/types';
import { eachDay } from '../lib/dates';

export const DEMO_LINK_HOST = 'https://example.com/demo-link/';

const NAMES: [string, number, string][] = [
  ['[예시] 무선 블루투스 이어폰', 39900, '가전디지털'],
  ['[예시] 스테인리스 텀블러 500ml', 12900, '주방용품'],
  ['[예시] 접이식 캠핑 의자', 24800, '스포츠/레저'],
  ['[예시] 대용량 보조배터리 20000mAh', 29900, '가전디지털'],
  ['[예시] 유기농 그래놀라 1kg', 15900, '식품'],
  ['[예시] 고양이 스크래쳐 하우스', 21900, '반려동물용품'],
  ['[예시] 극세사 차렵이불 퀸', 45900, '홈인테리어'],
  ['[예시] 어린이 원목 블록 세트', 32000, '완구/취미'],
  ['[예시] 데일리 수분 크림 80ml', 18500, '뷰티'],
  ['[예시] 차량용 핸드폰 거치대', 13900, '자동차용품'],
];

function rand(seed: number) {
  let s = seed % 2147483647;
  if (s <= 0) s += 2147483646;
  return () => (s = (s * 16807) % 2147483647) / 2147483647;
}

function demoProducts(seed: number, count: number, keyword?: string): Product[] {
  const r = rand(seed);
  const start = Math.floor(r() * NAMES.length);
  return Array.from({ length: Math.min(count, NAMES.length) }, (_, i) => {
    const [name, price, category] = NAMES[(start + i) % NAMES.length];
    const id = 900000 + ((start + i) % NAMES.length);
    return {
      productId: id,
      productName: keyword ? `${name} (${keyword})` : name,
      productPrice: Math.round((price * (0.85 + r() * 0.3)) / 100) * 100,
      productUrl: `${DEMO_LINK_HOST}${id}`,
      isRocket: r() > 0.35,
      isFreeShipping: r() > 0.5,
      categoryName: category,
      rank: i + 1,
    };
  });
}

function seedOf(text: string): number {
  let h = 7;
  for (const ch of text) h = (h * 31 + ch.charCodeAt(0)) % 1000003;
  return h + 1;
}

export function demoReports(range: DateRange): ReportBundle {
  const days = eachDay(range);
  const bundle: ReportBundle = { clicks: [], orders: [], cancels: [], commission: [] };
  let orderNo = 1;
  for (const date of days) {
    const r = rand(seedOf(date));
    for (const subId of ['blog', 'insta']) {
      const clicks = Math.floor((subId === 'blog' ? 60 : 25) + r() * 40);
      bundle.clicks.push({ date, subId, click: clicks });
      const orders = Math.floor(clicks * (0.02 + r() * 0.03));
      let dayCommission = 0;
      for (let i = 0; i < orders; i++) {
        const [name, price, category] = NAMES[Math.floor(r() * NAMES.length)];
        const quantity = r() > 0.85 ? 2 : 1;
        const gmv = price * quantity;
        const commission = Math.round(gmv * 0.03);
        dayCommission += commission;
        const row: OrderRow = {
          date,
          subId,
          orderId: 5000000 + orderNo++,
          productId: 900000 + NAMES.findIndex((n) => n[0] === name),
          productName: name,
          quantity,
          gmv,
          commissionRate: 3,
          commission,
          categoryName: category,
        };
        bundle.orders.push(row);
        if (r() > 0.93) {
          bundle.cancels.push({ ...row, orderDate: date });
          dayCommission -= commission;
        }
      }
      bundle.commission.push({ date, subId, commission: dayCommission });
    }
  }
  return bundle;
}

const wait = (ms: number) => new Promise((res) => setTimeout(res, ms));

export function createDemoClient(): ApiClient {
  return {
    async searchProducts(keyword) {
      await wait(300);
      return { landingUrl: `${DEMO_LINK_HOST}search`, products: demoProducts(seedOf(keyword), 10, keyword) };
    },
    async goldbox() {
      await wait(300);
      return demoProducts(11, 10);
    },
    async bestCategory(categoryId) {
      await wait(300);
      return demoProducts(categoryId, 10);
    },
    async coupangPL() {
      await wait(300);
      return demoProducts(77, 8);
    },
    async deeplink(urls) {
      await wait(250);
      return urls.map((u) => ({ originalUrl: u, shortenUrl: `${DEMO_LINK_HOST}${seedOf(u).toString(36)}` }));
    },
    async reports(range) {
      await wait(400);
      return demoReports(range);
    },
  };
}
