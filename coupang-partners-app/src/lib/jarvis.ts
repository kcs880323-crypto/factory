// 자비스(찰스님 PC 비서)와 함께 쓰기.
// 자비스 🛒 '내 제휴 상품 → 📥 파트너스 매니저에서 가져오기'가 이 CSV를 그대로 읽는다
// (자비스 shop_connect.import_csv: 첫 줄이 정확히 아래 머리글이어야 하고, link.coupang.com·coupa.ng 링크만 받는다).
import { kstYmd } from './dates';
import { classifyUrl } from './links';

export const JARVIS_CSV_HEAD = '이름,링크,가격,채널,메모,저장일';

export interface CsvLink {
  title: string;
  url: string;
  price?: number;
  subId?: string;
  memo?: string;
  createdAt: number;
  source: string;
}

export interface CsvResult {
  csv: string;
  count: number;
  /** 뺀 링크 수와 이유 (예시 링크, 파트너스 링크가 아님) */
  skipped: { demo: number; notPartner: number };
}

const cell = (v: unknown) => `"${String(v ?? '').replace(/"/g, '""')}"`;

/**
 * 링크 보관함을 CSV로. 예시 데이터 모드에서 만든 링크는 늘 뺀다.
 * onlyPartner면 파트너스 링크(link.coupang.com·coupa.ng)만 넣는다 — 자비스로 보낼 때.
 */
export function linksToCsv(links: CsvLink[], opts: { onlyPartner?: boolean } = {}): CsvResult {
  const skipped = { demo: 0, notPartner: 0 };
  const rows: string[] = [];
  for (const l of links) {
    if (l.source === 'demo' || l.url.includes('example.com')) {
      skipped.demo++;
      continue;
    }
    if (opts.onlyPartner && classifyUrl(l.url) !== 'partner') {
      skipped.notPartner++;
      continue;
    }
    const day = kstYmd(new Date(l.createdAt));
    rows.push(
      [l.title, l.url, l.price ?? '', l.subId ?? '', l.memo ?? '', `${day.slice(0, 4)}-${day.slice(4, 6)}-${day.slice(6, 8)}`]
        .map(cell)
        .join(','),
    );
  }
  return { csv: [JARVIS_CSV_HEAD, ...rows].join('\n'), count: rows.length, skipped };
}

export function skippedText(s: CsvResult['skipped']): string {
  const parts = [s.demo ? `예시 링크 ${s.demo}개` : '', s.notPartner ? `파트너스 링크가 아닌 것 ${s.notPartner}개` : ''].filter(Boolean);
  return parts.length ? ` (뺀 것: ${parts.join(', ')})` : '';
}

/** 쿠팡 파트너스에 등록한 내 채널(웹사이트·블로그·SNS). https 주소만. */
export interface SiteLink {
  label: string;
  url: string;
}

export const DEFAULT_SITES: SiteLink[] = [{ label: '찰스의 추천템', url: 'https://kcs880323-crypto.github.io/factory/docs/' }];

export function siteProblem(label: string, url: string): string | null {
  if (!label.trim()) return '이름을 적어 주세요.';
  if (label.trim().length > 20) return '이름은 20자까지예요.';
  try {
    const u = new URL(url.trim());
    if (u.protocol !== 'https:') return 'https:// 로 시작하는 주소만 넣을 수 있어요.';
  } catch {
    return '주소를 확인해 주세요. https:// 로 시작해야 해요.';
  }
  return null;
}
