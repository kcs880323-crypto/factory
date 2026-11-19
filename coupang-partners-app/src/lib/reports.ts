// 리포트 4종(클릭·주문·취소·수익)을 화면용 요약으로 묶는다.
import type { DateRange, ReportBundle } from '../api/types';
import { eachDay } from './dates';

export interface DayPoint {
  date: string;
  clicks: number;
  orders: number;
  commission: number;
}

export interface ProductPoint {
  key: string;
  name: string;
  orders: number;
  quantity: number;
  gmv: number;
  commission: number;
}

export interface ChannelPoint {
  subId: string;
  clicks: number;
  orders: number;
  commission: number;
}

export interface ReportSummary {
  clicks: number;
  orders: number; // 주문 건수 (주문번호 기준)
  quantity: number;
  gmv: number; // 판매액
  cancels: number;
  cancelGmv: number;
  commission: number; // 수익
  conversion: number; // 주문 ÷ 클릭
  epc: number; // 클릭당 수익
  days: DayPoint[];
  topProducts: ProductPoint[];
  channels: ChannelPoint[];
}

const NO_CHANNEL = '(채널 없음)';

export function summarize(bundle: ReportBundle, range: DateRange): ReportSummary {
  const days = new Map<string, DayPoint>(eachDay(range).map((d) => [d, { date: d, clicks: 0, orders: 0, commission: 0 }]));
  const day = (d: string) => {
    let p = days.get(d);
    if (!p) {
      p = { date: d, clicks: 0, orders: 0, commission: 0 };
      days.set(d, p);
    }
    return p;
  };
  const channels = new Map<string, ChannelPoint>();
  const channel = (s?: string) => {
    const k = s || NO_CHANNEL;
    let p = channels.get(k);
    if (!p) {
      p = { subId: k, clicks: 0, orders: 0, commission: 0 };
      channels.set(k, p);
    }
    return p;
  };

  let clicks = 0;
  for (const r of bundle.clicks) {
    clicks += r.click;
    day(r.date).clicks += r.click;
    channel(r.subId).clicks += r.click;
  }

  const orderIds = new Set<string>();
  const orderIdsByDay = new Map<string, Set<string>>();
  const orderIdsByChannel = new Map<string, Set<string>>();
  let quantity = 0;
  let gmv = 0;
  let orderCommission = 0;
  const products = new Map<string, ProductPoint & { ids: Set<string> }>();
  bundle.orders.forEach((r, i) => {
    const id = r.orderId !== undefined && r.orderId !== null && r.orderId !== '' ? String(r.orderId) : `row-${i}`;
    orderIds.add(id);
    if (!orderIdsByDay.has(r.date)) orderIdsByDay.set(r.date, new Set());
    orderIdsByDay.get(r.date)!.add(id);
    const ck = r.subId || NO_CHANNEL;
    if (!orderIdsByChannel.has(ck)) orderIdsByChannel.set(ck, new Set());
    orderIdsByChannel.get(ck)!.add(id);
    quantity += r.quantity ?? 0;
    gmv += r.gmv ?? 0;
    orderCommission += r.commission ?? 0;

    const pk = String(r.productId ?? r.productName ?? `상품 ${i + 1}`);
    let p = products.get(pk);
    if (!p) {
      p = { key: pk, name: r.productName || pk, orders: 0, quantity: 0, gmv: 0, commission: 0, ids: new Set() };
      products.set(pk, p);
    }
    p.ids.add(id);
    p.quantity += r.quantity ?? 0;
    p.gmv += r.gmv ?? 0;
    p.commission += r.commission ?? 0;
  });
  for (const [d, ids] of orderIdsByDay) day(d).orders = ids.size;
  for (const [c, ids] of orderIdsByChannel) channel(c).orders = ids.size;

  let cancelGmv = 0;
  let cancelCommission = 0;
  const cancelIds = new Set<string>();
  bundle.cancels.forEach((r, i) => {
    cancelIds.add(r.orderId !== undefined && r.orderId !== null && r.orderId !== '' ? String(r.orderId) : `cancel-${i}`);
    cancelGmv += r.gmv ?? 0;
    cancelCommission += r.commission ?? 0;
  });

  // 수익은 수익 리포트가 있으면 그것을 쓰고, 비어 있으면 주문 수수료 - 취소 수수료로 추정한다.
  let commission: number;
  if (bundle.commission.length > 0) {
    commission = 0;
    for (const r of bundle.commission) {
      commission += r.commission;
      day(r.date).commission += r.commission;
      channel(r.subId).commission += r.commission;
    }
  } else {
    commission = orderCommission - cancelCommission;
    for (const r of bundle.orders) {
      day(r.date).commission += r.commission ?? 0;
      channel(r.subId).commission += r.commission ?? 0;
    }
  }

  const orders = orderIds.size;
  return {
    clicks,
    orders,
    quantity,
    gmv,
    cancels: cancelIds.size,
    cancelGmv,
    commission,
    conversion: clicks > 0 ? orders / clicks : 0,
    epc: clicks > 0 ? commission / clicks : 0,
    days: [...days.values()].sort((a, b) => a.date.localeCompare(b.date)),
    topProducts: [...products.values()]
      .map(({ ids, ...p }) => ({ ...p, orders: ids.size }))
      .sort((a, b) => b.commission - a.commission || b.gmv - a.gmv)
      .slice(0, 5),
    channels: [...channels.values()].sort((a, b) => b.commission - a.commission || b.clicks - a.clicks),
  };
}
