// 여러 화면에서 같이 쓰는 큰 부품: 모드 안내, 상품 카드, 링크 카드, 호출 사용량.
import { router } from 'expo-router';
import { useState } from 'react';
import { Alert, Linking, Platform, View } from 'react-native';

import type { Product } from '../api/types';
import { dateTimeText, minutesText, shortUrl, won } from '../lib/format';
import { productPageUrl } from '../lib/links';
import { errorText, useApp, type SavedLink } from '../store/AppContext';
import { Badge, Banner, Button, Card, IconButton, Row, T, Thumb } from './kit';
import { useTheme } from './theme';

export const PARTNERS_SITE = 'https://partners.coupang.com/';

export function openUrl(url: string) {
  Linking.openURL(url).catch(() => undefined);
}

/** 확인 창 (웹 미리보기에서는 브라우저 confirm) */
export function confirm(title: string, message: string, ok: string, onOk: () => void) {
  if (Platform.OS === 'web') {
    if (globalThis.confirm?.(`${title}\n\n${message}`)) onOk();
    return;
  }
  Alert.alert(title, message, [
    { text: '취소', style: 'cancel' },
    { text: ok, style: 'destructive', onPress: onOk },
  ]);
}

export function ModeBanner({ compact }: { compact?: boolean }) {
  const { mode, updateSettings } = useApp();
  if (mode === 'demo') {
    return (
      <Banner
        tone="demo"
        icon="eye"
        title="예시 데이터 모드"
        action={<Button small kind="ghost" label="예시 모드 끄기" onPress={() => updateSettings({ demoMode: false })} />}>
        지금 보이는 상품·링크·실적은 화면을 둘러보기 위한 가짜 값이에요. 실제 쿠팡 데이터가 아닙니다.
      </Banner>
    );
  }
  if (mode === 'manual') {
    if (compact) return null;
    return (
      <Banner
        tone="info"
        icon="info"
        title="수동 모드 (API 키 없음)"
        action={
          <Row>
            <Button small kind="secondary" label="API 키 넣기" icon="key" onPress={() => router.push('/settings')} />
            <Button small kind="ghost" label="예시로 둘러보기" onPress={() => updateSettings({ demoMode: true })} />
          </Row>
        }>
        파트너스 사이트에서 만든 링크를 보관함에 붙여 넣고, 홍보 글을 만들 수 있어요. 상품 검색·자동 링크 변환·실적 조회는 API 키를 받은 뒤 열려요.
      </Banner>
    );
  }
  if (compact) return null;
  return <UsageMeter />;
}

export function UsageMeter() {
  const { usage } = useApp();
  const t = useTheme();
  const ratio = usage.limit ? usage.used / usage.limit : 0;
  const color = ratio >= 1 ? t.danger : ratio >= 0.7 ? t.accent : t.primary;
  return (
    <View style={{ gap: 6 }}>
      <Row style={{ justifyContent: 'space-between' }}>
        <T v="caption">최근 1시간 API 호출</T>
        <T v="caption" color={color}>
          {usage.used} / {usage.limit}회{usage.waitMs > 0 ? ` · ${minutesText(usage.waitMs)} 뒤 1회 가능` : ''}
        </T>
      </Row>
      <View style={{ height: 6, borderRadius: 3, backgroundColor: t.cardAlt, overflow: 'hidden' }}>
        <View style={{ width: `${Math.min(100, ratio * 100)}%`, height: 6, backgroundColor: color }} />
      </View>
    </View>
  );
}

/** 상품을 보관함에 저장. API로 짧은 링크를 만들고, 실패하면 상품에 붙어 온 추적 링크를 그대로 쓴다. */
export function useSaveProduct() {
  const { client, mode, settings, addLink, links, showToast } = useApp();
  return async (p: Product): Promise<SavedLink> => {
    const existing = links.find((l) => l.productId === String(p.productId) && l.source !== 'manual');
    if (existing) {
      showToast('이미 보관함에 있는 상품이에요');
      return existing;
    }
    let url = p.productUrl;
    if (client) {
      try {
        const [d] = await client.deeplink([productPageUrl(p.productId)], settings.subId || undefined);
        if (d?.shortenUrl) url = d.shortenUrl;
      } catch (e) {
        showToast(`짧은 링크를 못 만들어 긴 추적 링크로 저장했어요 (${errorText(e)})`);
      }
    }
    const saved = await addLink({
      title: p.productName,
      url,
      originalUrl: productPageUrl(p.productId),
      productId: String(p.productId),
      price: p.productPrice,
      image: p.productImage,
      isRocket: p.isRocket,
      subId: settings.subId || undefined,
      source: mode === 'demo' ? 'demo' : 'api',
    });
    showToast('보관함에 저장했어요');
    return saved;
  };
}

export function ProductCard({ p }: { p: Product }) {
  const { copy } = useApp();
  const save = useSaveProduct();
  const [busy, setBusy] = useState(false);
  const run = async (fn: () => Promise<unknown>) => {
    if (busy) return;
    setBusy(true);
    try {
      await fn();
    } finally {
      setBusy(false);
    }
  };
  return (
    <Card style={{ gap: 8 }}>
      <Row gap={12} style={{ alignItems: 'flex-start' }}>
        <Thumb uri={p.productImage} />
        <View style={{ flex: 1, gap: 4 }}>
          <T v="body" numberOfLines={2} style={{ fontWeight: '600' }}>
            {p.productName}
          </T>
          <T v="h3" color="primary">
            {won(p.productPrice)}
          </T>
          <Row gap={4} style={{ flexWrap: 'wrap' }}>
            {p.rank ? <Badge label={`${p.rank}위`} tone="info" /> : null}
            {p.isRocket ? <Badge label="로켓배송" tone="info" /> : null}
            {p.isFreeShipping ? <Badge label="무료배송" tone="primary" /> : null}
            {p.categoryName ? <Badge label={p.categoryName} tone="accent" /> : null}
          </Row>
        </View>
      </Row>
      <Row style={{ justifyContent: 'space-around', opacity: busy ? 0.5 : 1 }}>
        <IconButton icon="copy" label="링크 복사" onPress={() => copy(p.productUrl, '추적 링크를 복사했어요')} />
        <IconButton icon="save" label="보관함 저장" onPress={() => run(() => save(p))} />
        <IconButton
          icon="write"
          label="글 만들기"
          onPress={() =>
            run(async () => {
              const l = await save(p);
              router.push({ pathname: '/compose', params: { linkId: l.id } });
            })
          }
        />
        <IconButton icon="open" label="상품 보기" onPress={() => openUrl(p.productUrl)} />
      </Row>
    </Card>
  );
}

export function LinkCard({ link }: { link: SavedLink }) {
  const { copy, share, removeLink } = useApp();
  const t = useTheme();
  return (
    <Card style={{ gap: 8 }}>
      <Row gap={12} style={{ alignItems: 'flex-start' }}>
        <Thumb uri={link.image} size={56} />
        <View style={{ flex: 1, gap: 3 }}>
          <T v="body" numberOfLines={2} style={{ fontWeight: '600' }}>
            {link.title}
          </T>
          <T v="caption" color="info" numberOfLines={1} selectable>
            {shortUrl(link.url)}
          </T>
          <Row gap={4} style={{ flexWrap: 'wrap' }}>
            {link.price ? <Badge label={won(link.price)} tone="primary" /> : null}
            {link.subId ? <Badge label={`채널 ${link.subId}`} tone="accent" /> : null}
            {link.source === 'demo' ? <Badge label="예시" tone="demo" /> : null}
            {link.source === 'manual' ? <Badge label="직접 저장" tone="info" /> : null}
            <T v="caption" color={t.faint}>
              {dateTimeText(link.createdAt)}
            </T>
          </Row>
          {link.memo ? (
            <T v="sub" numberOfLines={2}>
              {link.memo}
            </T>
          ) : null}
        </View>
      </Row>
      <Row style={{ justifyContent: 'space-around' }}>
        <IconButton icon="copy" label="복사" onPress={() => copy(link.url, '링크를 복사했어요')} />
        <IconButton icon="share" label="공유" onPress={() => share(link.url)} />
        <IconButton icon="write" label="글 만들기" onPress={() => router.push({ pathname: '/compose', params: { linkId: link.id } })} />
        <IconButton icon="open" label="열기" onPress={() => openUrl(link.url)} />
        <IconButton
          icon="trash"
          label="삭제"
          color={t.danger}
          onPress={() => confirm('링크 삭제', `「${link.title}」을(를) 보관함에서 지울까요?`, '삭제', () => removeLink(link.id))}
        />
      </Row>
    </Card>
  );
}

export function CacheNote({ fetchedAt, fromCache }: { fetchedAt: number; fromCache: boolean }) {
  const { now } = useApp();
  const mins = Math.floor((now - fetchedAt) / 60000);
  return (
    <T v="caption">
      {fromCache
        ? `${mins < 1 ? '방금' : `${mins}분 전`} 불러온 결과예요 (API 호출 없이 저장된 것). 아래로 당기면 새로 불러와요.`
        : `방금 새로 불러왔어요 · ${dateTimeText(fetchedAt)}`}
    </T>
  );
}
