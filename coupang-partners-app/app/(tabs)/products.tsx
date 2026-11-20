import { router } from 'expo-router';
import { useCallback, useState } from 'react';
import { ScrollView, View } from 'react-native';

import { BEST_CATEGORIES } from '@/src/api/categories';
import { API_AVAILABLE } from '@/src/lib/hosting';
import type { Product, SearchResult } from '@/src/api/types';
import { errorText, useApp, type Cached, type Mode } from '@/src/store/AppContext';
import { Banner, Button, Chip, Empty, Field, Loading, Row, Screen, T } from '@/src/ui/kit';
import { CacheNote, ModeBanner, PARTNERS_SITE, ProductCard } from '@/src/ui/parts';

type Tab = 'search' | 'goldbox' | 'best' | 'pl';
const TABS: { key: Tab; label: string }[] = [
  { key: 'search', label: '검색' },
  { key: 'goldbox', label: '골드박스' },
  { key: 'best', label: '카테고리 베스트' },
  { key: 'pl', label: '쿠팡 PL' },
];
const HOUR = 60 * 60 * 1000;

export default function Products() {
  const { mode, cached, settings, updateSettings, copy } = useApp();
  const [tab, setTab] = useState<Tab>('search');
  const [keyword, setKeyword] = useState('');
  const [category, setCategory] = useState(BEST_CATEGORIES[0].id);
  // 결과·오류에는 어느 모드·탭에서 받은 것인지 붙여 둔다. 모드가 바뀌면(예시 ↔ 실제) 이전 결과를 보이지 않게.
  const [res, setRes] = useState<(Cached<Product[]> & { mode: Mode; tab: Tab; landingUrl?: string }) | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<{ mode: Mode; tab: Tab; text: string } | null>(null);
  const subId = settings.subId || undefined;
  const result = res && res.mode === mode && res.tab === tab ? res : null;
  const landingUrl = result?.landingUrl;
  const error = err && err.mode === mode && err.tab === tab ? err.text : null;

  const load = useCallback(
    async (which: Tab, opts: { force?: boolean; keyword?: string; category?: number } = {}) => {
      setErr(null);
      setLoading(true);
      try {
        let r: Cached<Product[]>;
        let landing: string | undefined;
        if (which === 'search') {
          const kw = (opts.keyword ?? keyword).trim();
          if (!kw) return;
          const sr = await cached<SearchResult>(`search.${kw}.${subId ?? ''}`, HOUR, (c) => c.searchProducts(kw, { subId, imageSize: '230x230' }), opts.force);
          r = { ...sr, data: sr.data.products };
          landing = sr.data.landingUrl;
        } else if (which === 'goldbox') {
          r = await cached(`goldbox.${subId ?? ''}`, HOUR, (c) => c.goldbox({ subId, imageSize: '230x230' }), opts.force);
        } else if (which === 'best') {
          const id = opts.category ?? category;
          r = await cached(`best.${id}.${subId ?? ''}`, HOUR, (c) => c.bestCategory(id, { subId, limit: 20, imageSize: '230x230' }), opts.force);
        } else {
          r = await cached(`pl.${subId ?? ''}`, HOUR, (c) => c.coupangPL({ subId, limit: 20, imageSize: '230x230' }), opts.force);
        }
        setRes({ ...r, mode, tab: which, landingUrl: landing });
      } catch (e) {
        setErr({ mode, tab: which, text: errorText(e) });
      } finally {
        setLoading(false);
      }
    },
    [cached, category, keyword, subId, mode],
  );

  const pick = (next: Tab) => {
    setTab(next);
    if (next !== 'search') load(next);
  };

  if (mode === 'manual') {
    return (
      <Screen>
        <Banner tone="info" icon="key" title={API_AVAILABLE ? '상품 검색은 API 키가 있어야 해요' : '이 화면에서는 상품 검색을 못 해요'}>
          {API_AVAILABLE
            ? '쿠팡 파트너스 API 키는 가입 후 일정 활동을 거쳐 승인을 받으면 발급받을 수 있다고 안내돼 있어요. 그 전에는 파트너스 사이트에서 상품을 찾아 링크를 만든 뒤, 링크 탭에 붙여 넣어 쓰세요.'
            : 'Claude 화면은 쿠팡 서버로 요청을 보낼 수 없어요. 파트너스 사이트에서 상품을 찾아 링크를 만든 뒤 링크 탭에 붙여 넣거나, 휴대폰 앱·직접 배포한 웹사이트에서 API 키를 넣어 쓰세요.'}
        </Banner>
        <Button label="파트너스 사이트에서 상품 찾기" icon="open" href={PARTNERS_SITE} />
        <Button label="만든 링크 붙여 넣기" icon="paste" kind="secondary" onPress={() => router.push('/links')} />
        {API_AVAILABLE ? <Button label="API 키 넣기" icon="key" kind="ghost" onPress={() => router.push('/settings')} /> : null}
        <Button label="예시 데이터로 화면 둘러보기" icon="eye" kind="ghost" onPress={() => updateSettings({ demoMode: true })} />
      </Screen>
    );
  }

  return (
    <Screen refreshing={loading && !!result} onRefresh={() => load(tab, { force: true })}>
      <ModeBanner compact />
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 8 }}>
        {TABS.map((x) => (
          <Chip key={x.key} label={x.label} selected={tab === x.key} onPress={() => pick(x.key)} />
        ))}
      </ScrollView>

      {tab === 'search' ? (
        <View style={{ gap: 8 }}>
          <Field
            placeholder="예: 물티슈, 캠핑의자, 무선 이어폰"
            value={keyword}
            onChangeText={setKeyword}
            returnKeyType="search"
            onSubmitEditing={() => load('search')}
            right={<Button label="검색" onPress={() => load('search')} loading={loading} />}
          />
          <T v="caption">검색 한 번에 API 1회를 써요. 같은 검색어는 1시간 동안 저장된 결과를 다시 보여 줘요.</T>
        </View>
      ) : null}

      {tab === 'goldbox' ? <T v="caption">골드박스는 쿠팡의 하루 특가예요. 매일 오전 7시 30분쯤 바뀌어요.</T> : null}

      {tab === 'best' ? (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 8 }}>
          {BEST_CATEGORIES.map((c) => (
            <Chip
              key={c.id}
              label={c.name}
              selected={category === c.id}
              onPress={() => {
                setCategory(c.id);
                load('best', { category: c.id });
              }}
            />
          ))}
        </ScrollView>
      ) : null}

      {tab === 'pl' ? <T v="caption">쿠팡 자체 브랜드(PL) 상품이에요.</T> : null}

      {error ? <Banner tone="danger" icon="warn" title="불러오지 못했어요">{error}</Banner> : null}

      {loading && !result ? <Loading /> : null}

      {result ? (
        <View style={{ gap: 10 }}>
          <CacheNote fetchedAt={result.fetchedAt} fromCache={result.fromCache} />
          {tab === 'search' && landingUrl ? (
            <Row style={{ justifyContent: 'space-between' }}>
              <T v="sub">검색 결과 페이지 전체를 소개하는 링크</T>
              <Button small kind="secondary" icon="copy" label="복사" onPress={() => copy(landingUrl, '검색 결과 링크를 복사했어요')} />
            </Row>
          ) : null}
          {result.data.length === 0 ? (
            <Empty icon="search" title="결과가 없어요" />
          ) : (
            result.data.map((p) => <ProductCard key={`${p.productId}-${p.rank ?? ''}`} p={p} />)
          )}
        </View>
      ) : !loading && tab === 'search' && !error ? (
        <Empty icon="search" title="소개할 상품을 찾아보세요">
          결과에서 바로 링크를 복사하거나, 보관함에 저장하거나, 홍보 글을 만들 수 있어요.
        </Empty>
      ) : null}
    </Screen>
  );
}
