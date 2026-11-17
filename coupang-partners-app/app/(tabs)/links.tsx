import * as Clipboard from 'expo-clipboard';
import { useMemo, useState } from 'react';
import { Platform, ScrollView, View } from 'react-native';

import { toNumber } from '@/src/api/client';
import { classifyUrl, extractUrl, productIdFromUrl } from '@/src/lib/links';
import { errorText, useApp } from '@/src/store/AppContext';
import { Banner, Button, Card, Chip, Empty, Field, Row, Screen, Section, T } from '@/src/ui/kit';
import { LinkCard, ModeBanner, PARTNERS_SITE } from '@/src/ui/parts';

export default function Links() {
  const { mode, client, settings, links, addLink, showToast, copy } = useApp();
  const [raw, setRaw] = useState('');
  const [title, setTitle] = useState('');
  const [price, setPrice] = useState('');
  const [memo, setMemo] = useState('');
  const [subId, setSubId] = useState(settings.subId);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState('');

  const url = extractUrl(raw);
  const kind = raw.trim() ? classifyUrl(url) : null;
  const channels = useMemo(() => [...new Set([settings.subId, ...settings.channels].filter(Boolean))], [settings]);

  const paste = async () => {
    let text = '';
    try {
      text = await Clipboard.getStringAsync();
    } catch {
      text = '';
    }
    if (!text) {
      // 웹(특히 아티팩트 화면)은 클립보드 읽기를 막는다. 입력칸에 직접 붙여 넣으면 된다.
      showToast(Platform.OS === 'web' ? '입력칸을 누르고 Ctrl+V(휴대폰은 길게 눌러 붙여넣기) 해 주세요' : '클립보드가 비어 있어요');
      return;
    }
    setRaw(text);
    // 쿠팡 앱 공유 문구에는 상품명이 앞에 붙어 오는 경우가 많다
    const before = text.split(/https?:\/\//)[0].replace(/\[쿠팡\]|쿠팡/g, '').trim();
    if (!title && before.length >= 2 && before.length <= 80) setTitle(before);
  };

  const reset = () => {
    setRaw('');
    setTitle('');
    setPrice('');
    setMemo('');
    setError(null);
  };

  const save = async () => {
    setError(null);
    if (kind === 'invalid' || kind === null) return setError('주소를 확인해 주세요. https:// 로 시작하는 주소를 붙여 넣어요.');
    if (kind === 'other') return setError('쿠팡 주소나 파트너스 링크가 아니에요.');
    setBusy(true);
    try {
      let finalUrl = url;
      let source: 'api' | 'manual' | 'demo' = 'manual';
      if (kind === 'coupang') {
        if (!client) return setError('이 주소는 일반 쿠팡 주소라 그대로 올리면 수익이 잡히지 않아요. 파트너스 사이트에서 링크를 만든 뒤 붙여 넣어 주세요.');
        const [d] = await client.deeplink([url], subId || undefined);
        if (!d?.shortenUrl) throw new Error('쿠팡이 링크를 돌려주지 않았어요.');
        finalUrl = d.shortenUrl;
        source = mode === 'demo' ? 'demo' : 'api';
      }
      await addLink({
        title: title.trim() || '쿠팡 추천 상품',
        url: finalUrl,
        originalUrl: kind === 'coupang' ? url : undefined,
        productId: productIdFromUrl(url),
        price: price ? toNumber(price) || undefined : undefined,
        memo: memo.trim() || undefined,
        subId: subId || undefined,
        source,
      });
      if (kind === 'coupang') await copy(finalUrl, '제휴 링크로 바꿔 저장하고 복사했어요');
      else showToast('보관함에 저장했어요');
      reset();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const shown = filter.trim()
    ? links.filter((l) => `${l.title} ${l.memo ?? ''} ${l.subId ?? ''}`.toLowerCase().includes(filter.trim().toLowerCase()))
    : links;

  return (
    <Screen>
      <ModeBanner compact />
      <Card>
        <T v="h2">{client ? '링크 만들기 · 저장' : '파트너스 링크 저장'}</T>
        <Field
          placeholder={client ? '쿠팡 상품 주소 또는 파트너스 링크' : '파트너스 링크 (link.coupang.com/a/…)'}
          value={raw}
          onChangeText={setRaw}
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType="url"
          right={<Button kind="secondary" icon="paste" label="붙여넣기" onPress={paste} />}
        />
        {kind === 'partner' ? <Banner tone="success" icon="checked">파트너스 링크예요. 그대로 저장할 수 있어요.</Banner> : null}
        {kind === 'coupang' && client ? (
          <Banner tone="info" icon="bolt">
            {mode === 'demo'
              ? '예시 모드라 가짜 링크로 바뀌어요.'
              : '일반 쿠팡 주소예요. 저장할 때 API로 수익이 잡히는 파트너스 링크로 바꿔요 (API 1회).'}
          </Banner>
        ) : null}
        {kind === 'coupang' && !client ? (
          <Banner tone="warn" icon="warn" title="이 주소로는 수익이 안 잡혀요" action={<Button small kind="ghost" icon="open" label="파트너스 사이트 열기" href={PARTNERS_SITE} />}>
            일반 쿠팡 주소예요. 파트너스 사이트에서 이 상품의 링크를 만든 뒤 그 링크를 붙여 넣어 주세요.
          </Banner>
        ) : null}
        {kind === 'other' || kind === 'invalid' ? <Banner tone="danger" icon="warn">쿠팡 주소나 파트너스 링크가 아니에요.</Banner> : null}

        <Field label="상품 이름" placeholder="글에 들어갈 이름 (예: 캡형 물티슈 100매 10팩)" value={title} onChangeText={setTitle} />
        <Row gap={10} style={{ alignItems: 'flex-start' }}>
          <View style={{ flex: 1, minWidth: 0 }}>
            <Field label="가격 (선택)" placeholder="12900" value={price} onChangeText={setPrice} keyboardType="number-pad" />
          </View>
          <View style={{ flex: 1.4, minWidth: 0 }}>
            <Field label="메모 (선택)" placeholder="장점, 대상, 할인 정보" value={memo} onChangeText={setMemo} />
          </View>
        </Row>
        {channels.length > 0 ? (
          <View style={{ gap: 6 }}>
            <T v="h3">채널 ID</T>
            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 8 }}>
              <Chip label="없음" selected={!subId} onPress={() => setSubId('')} />
              {channels.map((c) => (
                <Chip key={c} label={c} selected={subId === c} onPress={() => setSubId(c)} />
              ))}
            </ScrollView>
            {!client ? <T v="caption">수동 모드에서는 메모용 표시예요. 파트너스 사이트에서 링크를 만들 때 같은 채널을 골라 주세요.</T> : null}
          </View>
        ) : null}
        {error ? <Banner tone="danger" icon="warn">{error}</Banner> : null}
        <Button
          label={kind === 'coupang' && client ? '파트너스 링크로 바꿔 저장' : '보관함에 저장'}
          icon="save"
          onPress={save}
          loading={busy}
          disabled={!raw.trim()}
        />
      </Card>

      <Section title={`보관함 ${links.length}개`}>
        {links.length > 4 ? <Field placeholder="이름·메모·채널로 찾기" value={filter} onChangeText={setFilter} /> : null}
        {shown.length === 0 ? (
          <Empty icon="link" title={links.length ? '찾는 링크가 없어요' : '아직 저장한 링크가 없어요'}>
            {links.length ? undefined : '저장한 링크는 이 휴대폰에만 보관돼요.'}
          </Empty>
        ) : (
          shown.map((l) => <LinkCard key={l.id} link={l} />)
        )}
      </Section>
    </Screen>
  );
}
