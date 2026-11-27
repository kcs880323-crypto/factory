// 홍보 글 만들기. 공정위 「추천·보증 심사지침」(2024.12.1 시행)에 맞춰
// 대가성 문구를 글의 맨 앞(첫 부분)에 둔다.

export const DEFAULT_DISCLOSURE =
  '이 포스팅은 쿠팡 파트너스 활동의 일환으로, 이에 따른 일정액의 수수료를 제공받습니다.';

export type Channel = 'blog' | 'instagram' | 'kakao' | 'threads';

export const CHANNELS: { key: Channel; label: string; hint: string }[] = [
  { key: 'blog', label: '블로그', hint: '제목 앞 [광고], 첫 줄에 대가성 문구' },
  { key: 'instagram', label: '인스타그램', hint: '캡션 첫 줄에 문구, 링크는 프로필·스토리에' },
  { key: 'kakao', label: '카톡·문자', hint: '짧게, 맨 앞에 [광고]' },
  { key: 'threads', label: '스레드·X', hint: '짧은 글 + #광고' },
];

export interface ComposeInput {
  title: string;
  url: string;
  price?: number;
  priceDate?: string;
  memo?: string;
  isRocket?: boolean;
  disclosure?: string;
  /** 검색 의도·문제 상황 한 줄 (블로그 SEO 제목·요약용) */
  problem?: string;
  /** 확인된 핵심 특징 목록 (최대 3개 권장) */
  features?: string[];
  /** 구매 전 체크·주의사항 */
  cautions?: string[];
  /** 함께 비교해 볼 만한 연관 상품 링크 */
  relatedLinks?: { title: string; url: string; price?: number }[];
}

const won = (n: number) => `${Math.round(n).toLocaleString('ko-KR')}원`;

function hashtagsFrom(title: string): string {
  const words = title
    .replace(/\[[^\]]*\]/g, ' ')
    .split(/[\s,/()·]+/)
    .map((w) => w.replace(/[^0-9A-Za-z가-힣]/g, ''))
    .filter((w) => w.length >= 2 && w.length <= 12)
    .slice(0, 3);
  return ['#광고', '#쿠팡파트너스', ...words.map((w) => `#${w}`)].join(' ');
}

export function composePost(channel: Channel, input: ComposeInput): string {
  const disclosure = (input.disclosure || DEFAULT_DISCLOSURE).trim();
  const title = input.title.trim() || '추천 상품';
  const price = input.price && input.price > 0 ? won(input.price) : '';
  const priceBasis = input.priceDate ? `${input.priceDate} 기준` : '글 쓴 시점 기준';
  const memo = input.memo?.trim();
  const rocket = input.isRocket ? '로켓배송' : '';

  switch (channel) {
    case 'blog': {
      const problem = input.problem?.trim();
      const features = (input.features ?? []).map((f) => f.trim()).filter(Boolean);
      const cautions = (input.cautions ?? []).map((c) => c.trim()).filter(Boolean);
      const related = input.relatedLinks ?? [];
      const headline = problem ? `[광고] ${problem.replace(/[.?!]+$/, '')}? ${title} 스펙·구매 전 체크` : `[광고] ${title}`;

      if (!problem && features.length === 0 && cautions.length === 0 && related.length === 0) {
        return [
          headline,
          '',
          `※ ${disclosure}`,
          '',
          `오늘 소개할 상품은 「${title}」입니다.`,
          memo ? `\n${memo}\n` : '',
          price || rocket ? `■ 가격: ${[price, rocket].filter(Boolean).join(' · ')} (${priceBasis}, 바뀔 수 있어요)` : '',
          `■ 상품 보러 가기: ${input.url}`,
          '',
          '※ 가격과 재고는 쿠팡 상품 페이지에서 최종 확인해 주세요.',
        ]
          .filter((l, i, a) => !(l === '' && a[i - 1] === ''))
          .join('\n')
          .trim();
      }

      const lines: string[] = [
        headline,
        '',
        `※ ${disclosure}`,
        '',
        '1. 3줄 핵심 요약',
        problem ? `- 이런 고민에 적합: ${problem}` : '',
        features.length ? `- 핵심 특징: ${features.slice(0, 3).join(' / ')}` : '',
        price || rocket ? `- 오늘 확인가: ${[price, rocket].filter(Boolean).join(' · ')} (${priceBasis})` : '',
        memo ? `\n${memo}` : '',
        '',
        `■ 상품 상세 스펙·현재 가격 확인: ${input.url}`,
      ];

      if (features.length) {
        lines.push('', '2. 구매 전 짚어볼 핵심 포인트');
        features.forEach((f, idx) => lines.push(`${idx + 1}) ${f}`));
      }

      lines.push('', '3. 구매 전 꼭 체크할 점');
      if (cautions.length) {
        cautions.forEach((c) => lines.push(`- ${c}`));
      } else {
        lines.push(`- 표기된 가격은 ${priceBasis}이며 판매처 사정에 따라 변동될 수 있어요.`);
        lines.push('- 구매 전 상세페이지에서 정확한 치수·옵션·배송 조건을 최종 확인해 주세요.');
      }

      if (related.length) {
        lines.push('', '4. 함께 비교해 볼 만한 추천템');
        related.slice(0, 3).forEach((r) => {
          const rp = r.price && r.price > 0 ? ` (${won(r.price)})` : '';
          lines.push(`- ${r.title}${rp}: ${r.url}`);
        });
      }

      lines.push('', `👉 상품 보러 가기: ${input.url}`, '', hashtagsFrom(title));
      return lines
        .filter((l, i, a) => !(l === '' && a[i - 1] === ''))
        .join('\n')
        .trim();
    }
    case 'instagram':
      return [
        `[광고] ${disclosure}`,
        '',
        `✔ ${title}`,
        price ? `💰 ${price}${rocket ? ` · ${rocket}` : ''} (게시 시점 기준)` : '',
        memo ?? '',
        '',
        '🔗 구매 링크는 프로필 링크에서 확인하세요',
        '',
        hashtagsFrom(title),
      ]
        .filter((l, i, a) => !(l === '' && a[i - 1] === ''))
        .join('\n')
        .trim();
    case 'kakao':
      return [
        `[광고] ${disclosure}`,
        '',
        title,
        price ? `${price}${rocket ? ` · ${rocket}` : ''}` : '',
        memo ?? '',
        `👉 ${input.url}`,
      ]
        .filter((l, i) => l !== '' || i === 1)
        .join('\n')
        .trim();
    case 'threads':
      return [`[광고] ${disclosure}`, '', `${title}${price ? ` — ${price}` : ''}`, memo ?? '', input.url, '', '#광고 #쿠팡파트너스']
        .filter((l, i, a) => !(l === '' && a[i - 1] === ''))
        .join('\n')
        .trim();
  }
}

/** 글의 첫 부분(앞 3줄 안)에 대가성 문구가 있는지 */
export function hasDisclosureOnTop(text: string, disclosure = DEFAULT_DISCLOSURE): boolean {
  const head = text.trim().split('\n').slice(0, 3).join('\n');
  return head.includes(disclosure.trim());
}

/** 문구가 앞에 없으면 맨 앞에 붙인다 (본문 중간에 있던 같은 문구는 지운다). */
export function ensureDisclosureOnTop(text: string, disclosure = DEFAULT_DISCLOSURE): string {
  if (hasDisclosureOnTop(text, disclosure)) return text;
  const d = disclosure.trim();
  const body = text
    .split('\n')
    .filter((l) => !l.includes(d))
    .join('\n')
    .trim();
  return `[광고] ${d}\n\n${body}`;
}
