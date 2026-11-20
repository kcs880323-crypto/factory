// 글·영상 화면이 함께 쓰는 상품 고르기: 보관함 링크(또는 직접 입력) + 종류(생활용품/여행) + 메모.
// 메모가 사실 근거가 된다 — 상품 페이지에서 직접 확인한 구성·일정·포함 사항만 적는다.
import { ScrollView, View } from 'react-native';

import { won } from '../lib/format';
import { affiliateOf, type ProductInfo, type ProductKind } from '../lib/productRules';
import type { SavedLink } from '../store/AppContext';
import { Badge, Card, Chip, Field, Row, T } from './kit';
import { VaultPickerButton } from './VaultPicker';

export interface PickState {
  linkId?: string;
  name: string;
  price: string;
  url: string;
  kind: ProductKind;
  memo: string;
}

function guessKind(l: SavedLink): ProductKind {
  return l.kind ?? (/여행|투어|항공|호텔|숙박|패키지|\d+\s*박/.test(`${l.title} ${l.category ?? ''}`) ? 'travel' : 'shop');
}

export function pickFromLink(l?: SavedLink): PickState {
  return l
    ? { linkId: l.id, name: '', price: '', url: '', kind: guessKind(l), memo: l.memo ?? '' }
    : { name: '', price: '', url: '', kind: 'shop', memo: '' };
}

export function toInfo(p: PickState, links: SavedLink[]): ProductInfo | null {
  const l = p.linkId ? links.find((x) => x.id === p.linkId) : undefined;
  if (l) {
    return {
      name: l.title,
      price: l.price ?? 0,
      url: l.url,
      category: l.category,
      rank: l.rank,
      tag: l.rank ? '카테고리 베스트' : undefined,
      rocket: l.isRocket,
      freeShip: l.isFreeShipping,
      memo: p.memo.trim().slice(0, 500) || undefined,
      kind: p.kind,
      affiliate: affiliateOf(l.url),
    };
  }
  const name = p.name.trim();
  if (name.length < 2) return null;
  const url = p.url.trim();
  return {
    name: name.slice(0, 120),
    price: Math.max(0, Number(p.price.replace(/[^\d]/g, '')) || 0),
    url: url.startsWith('https://') ? url : '',
    memo: p.memo.trim().slice(0, 500) || undefined,
    kind: p.kind,
    affiliate: affiliateOf(url),
  };
}

export function ProductInput({ value, onChange, links }: { value: PickState; onChange: (v: PickState) => void; links: SavedLink[] }) {
  const link = value.linkId ? links.find((l) => l.id === value.linkId) : undefined;
  const info = toInfo(value, links);
  return (
    <View style={{ gap: 10 }}>
      <View style={{ gap: 6 }}>
        <Row style={{ justifyContent: 'space-between', flexWrap: 'wrap' }}>
          <T v="h3">어떤 상품으로 할까요?</T>
          {links.length ? <VaultPickerButton links={links} selectedId={value.linkId} onPick={(l) => onChange(pickFromLink(l))} /> : null}
        </Row>
        {links.length ? (
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 8 }}>
            {(link && links.indexOf(link) >= 30 ? [link, ...links.slice(0, 29)] : links.slice(0, 30)).map((l) => (
              <Chip key={l.id} label={l.title.length > 18 ? `${l.title.slice(0, 17)}…` : l.title} selected={l.id === value.linkId} onPress={() => onChange(pickFromLink(l))} />
            ))}
            <Chip label="직접 입력" selected={!link} onPress={() => onChange(pickFromLink())} />
          </ScrollView>
        ) : (
          <T v="caption">보관함이 비어 있어서 직접 입력해요.</T>
        )}
      </View>

      {link ? (
        <Card style={{ gap: 4 }}>
          <T style={{ fontWeight: '700' }} numberOfLines={2}>
            {link.title}
          </T>
          <T v="caption" color="info" numberOfLines={1}>
            {link.url}
          </T>
          <Row gap={6} style={{ flexWrap: 'wrap' }}>
            {link.price ? <Badge label={won(link.price)} tone="primary" /> : <Badge label="가격 모름" tone="danger" />}
            {link.rank ? <Badge label={`베스트 ${link.rank}위`} tone="info" /> : null}
            {link.isRocket ? <Badge label="로켓배송" tone="info" /> : null}
          </Row>
        </Card>
      ) : (
        <Card>
          <Field label="상품 이름" value={value.name} onChangeText={(name) => onChange({ ...value, name })} placeholder="예: 초음파 가습기 3L 무드등" />
          <Field label="가격 (원)" value={value.price} onChangeText={(price) => onChange({ ...value, price })} keyboardType="number-pad" placeholder="예: 29800" />
          <Field label="구매 링크" value={value.url} onChangeText={(url) => onChange({ ...value, url })} placeholder="https://link.coupang.com/a/…" autoCapitalize="none" keyboardType="url" />
        </Card>
      )}

      <View style={{ gap: 6 }}>
        <T v="h3">종류</T>
        <Row gap={8}>
          <Chip label="🛒 생활용품" selected={value.kind === 'shop'} onPress={() => onChange({ ...value, kind: 'shop' })} />
          <Chip label="✈ 여행 상품" selected={value.kind === 'travel'} onPress={() => onChange({ ...value, kind: 'travel' })} />
        </Row>
      </View>

      <Field
        label="메모 (상품 페이지에서 확인한 것)"
        value={value.memo}
        onChangeText={(memo) => onChange({ ...value, memo })}
        multiline
        style={{ minHeight: 90 }}
        placeholder={value.kind === 'travel' ? '예: 3박 4일, 왕복 항공·호텔·조식 포함, 12월 출발' : '예: 물통 3L, 타이머 8시간, 무드등'}
        help="글·영상에 쓰는 사실은 상품 정보와 이 메모뿐이에요. 직접 확인한 것만 적어 주세요 (숫자도 여기 있는 것만 쓸 수 있어요)."
      />
      {info && info.affiliate === 'none' ? (
        <T v="caption" color="danger">
          이 링크는 파트너스(수수료) 링크가 아니에요 — 글·영상에는 &lsquo;수수료 없음&rsquo; 안내가 붙어요. 수익을 받으려면 파트너스 사이트에서 만든 link.coupang.com 링크를 쓰세요.
        </T>
      ) : null}
    </View>
  );
}
