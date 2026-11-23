// 네이버 상품 추천 — 자비스 🛒 '가장 핫한 물품'과 같은 셈법 (src/lib/naverTrend.ts).
import { router } from 'expo-router';
import { useCallback, useMemo, useState } from 'react';
import { View } from 'react-native';

import type { Product } from '@/src/api/types';
import { dateTimeText, won } from '@/src/lib/format';
import { API_AVAILABLE } from '@/src/lib/hosting';
import {
  kstMonth,
  momentum,
  momentumText,
  recommend,
  SEASON,
  TREND_CATEGORIES,
  trendWindow,
  type RecommendResult,
  type RecommendSources,
  type ScoredItem,
} from '@/src/lib/naverTrend';
import { errorText, useApp, type Cached } from '@/src/store/AppContext';
import { Badge, Banner, Button, Card, Chip, Empty, IconButton, Loading, Row, Screen, Section, T, Thumb } from '@/src/ui/kit';
import { useSaveProduct } from '@/src/ui/parts';
import { useTheme } from '@/src/ui/theme';

const SIX_HOURS = 6 * 60 * 60 * 1000;
const DEFAULT_CATS = ['living', 'kitchen', 'travel'];

/** 예시 모드용 가짜 추이 (화면에 '예시'로 표시) */
function demoTrend(keys: string[]): Record<string, number | null> {
  return Object.fromEntries(keys.map((k, i) => [k, Math.round((0.92 + ((k.length * 7 + i * 13) % 30) / 100) * 1000) / 1000]));
}

function toProduct(it: ScoredItem): Product {
  return {
    productId: it.id,
    productName: it.name,
    productPrice: it.price,
    productImage: it.image,
    productUrl: it.url,
    isRocket: it.isRocket,
    isFreeShipping: it.isFreeShipping,
    categoryName: it.category,
    rank: it.rank,
  };
}

export default function Recommend() {
  const { mode, client, naver, naverHub, naverShop, remember, links, settings } = useApp();
  const t = useTheme();
  const save = useSaveProduct();
  const [cats, setCats] = useState<string[]>(DEFAULT_CATS);
  const [res, setRes] = useState<(Cached<RecommendResult> & { demoTrend: boolean; mode: string }) | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [seasonMonth] = useState(() => kstMonth(new Date()));

  const useDemoTrend = mode === 'demo' && !naverHub;
  const result = res && res.mode === mode ? res : null;

  const run = useCallback(
    async (force = false) => {
      setErr(null);
      setLoading(true);
      try {
        const src: RecommendSources = {};
        if (naver && naverHub) {
          src.trend = async (list) => {
            const w = trendWindow(new Date());
            const out: Record<string, number | null> = {};
            for (let i = 0; i < list.length; i += 3) {
              const group = list.slice(i, i + 3);
              const rows = await naver.categoryTrend(
                group.map((c) => ({ name: c.name, param: c.naver })),
                w.startDate,
                w.endDate,
              );
              group.forEach((c, j) => (out[c.key] = rows[j] ? momentum(rows[j].data.map((d) => d.ratio)) : null));
            }
            return out;
          };
        } else if (mode === 'demo') {
          src.trend = async (list) => demoTrend(list.map((c) => c.key));
        }
        if (client) {
          const subId = settings.subId || undefined;
          src.goldbox = () => client.goldbox({ subId, imageSize: '230x230' });
          src.best = (id) => client.bestCategory(id, { subId, limit: 10, imageSize: '230x230' });
        } else if (naver && naverShop) {
          src.naverShop = (q) => naver.shopSearch(q, 5);
        }
        const key = `recommend.${[...cats].sort().join('-')}.${naverHub ? 'h' : ''}${naverShop ? 's' : ''}.${settings.subId}`;
        const r = await remember(key, SIX_HOURS, () => recommend(src, cats, new Date(), errorText), force);
        setRes({ ...r, demoTrend: useDemoTrend, mode });
      } catch (e) {
        setErr(errorText(e));
      } finally {
        setLoading(false);
      }
    },
    [naver, naverHub, naverShop, client, cats, remember, settings.subId, mode, useDemoTrend],
  );

  const seasonWords = useMemo(() => SEASON[seasonMonth] ?? [], [seasonMonth]);
  const trendRows = useMemo(() => {
    if (!result) return [];
    return cats
      .map((k) => ({ key: k, name: TREND_CATEGORIES.find((c) => c.key === k)?.name ?? k, m: result.data.trend[k] ?? null }))
      .sort((a, b) => (b.m ?? 0) - (a.m ?? 0));
  }, [result, cats]);
  const maxM = Math.max(1.3, ...trendRows.map((r) => r.m ?? 0));
  const seasonLinks = useMemo(
    () => links.filter((l) => seasonWords.some((w) => l.title.replace(/\s/g, '').includes(w.replace(/\s/g, '')))).slice(0, 5),
    [links, seasonWords],
  );

  const toggle = (k: string) => setCats((c) => (c.includes(k) ? (c.length > 1 ? c.filter((x) => x !== k) : c) : [...c, k]));

  const act = async (it: ScoredItem, then: 'post' | 'reel' | null) => {
    if (busyId) return;
    setBusyId(it.id + it.name);
    try {
      const l = await save(toProduct(it));
      if (then) router.push({ pathname: then === 'post' ? '/post' : '/reel', params: { linkId: l.id } });
    } finally {
      setBusyId(null);
    }
  };

  const noSources = !API_AVAILABLE || (!client && !naver);

  return (
    <Screen refreshing={loading && !!result} onRefresh={() => run(true)}>
      <View style={{ gap: 4 }}>
        <T v="title">지금 소개하기 좋은 상품</T>
        <T v="sub">
          자비스와 같은 셈법이에요: 쿠팡 순위(45) + 네이버 &lsquo;뜨는 분야&rsquo;(25) + 이달에 많이 찾는 말(15) + 영상으로 보여 주기 좋음(15). 판매량·화제성을 직접 잰 값은 아니에요.
        </T>
      </View>

      {noSources ? (
        <Banner tone="info" icon="info" title={API_AVAILABLE ? '키를 넣으면 추천이 더 정확해져요' : '이 화면에서는 바깥 API를 부를 수 없어요'}>
          {API_AVAILABLE
            ? '설정 → 네이버 API에 API HUB 키를 넣으면 뜨는 분야를, 쿠팡 파트너스 키를 넣으면 상품 후보(골드박스·카테고리 베스트)를 받아 와요. 예시 데이터 모드로 화면을 미리 볼 수도 있어요.'
            : 'Claude 화면은 네이버·쿠팡 서버로 요청을 보낼 수 없어요. 이달에 많이 찾는 말과 내 보관함으로 골라 보고, 뜨는 분야·상품 후보는 휴대폰 앱이나 직접 배포한 웹사이트에서 받아 보세요.'}
        </Banner>
      ) : null}

      <Section title="분야 고르기">
        <Row gap={8} style={{ flexWrap: 'wrap' }}>
          {TREND_CATEGORIES.map((c) => (
            <Chip key={c.key} label={c.name} selected={cats.includes(c.key)} onPress={() => toggle(c.key)} />
          ))}
        </Row>
        <T v="caption">
          {`네이버 ${naverHub ? `쇼핑인사이트 ${Math.ceil(cats.length / 3)}회` : '호출 없음'} · 쿠팡 ${
            client ? `${1 + cats.reduce((n, k) => n + (TREND_CATEGORIES.find((c) => c.key === k)?.coupang.length ?? 0), 0)}회` : '호출 없음'
          }${!client && naverShop ? ` · 네이버 쇼핑 검색 최대 ${cats.length * 2}회` : ''} — 같은 조합은 6시간 동안 다시 부르지 않아요.`}
        </T>
        <Button label={result ? '이 분야로 다시 추천' : '추천 받기'} icon="trend" onPress={() => run(false)} loading={loading} />
      </Section>

      {err ? (
        <Banner tone="danger" icon="warn" title="추천을 만들지 못했어요">
          {err}
        </Banner>
      ) : null}
      {loading && !result ? <Loading label="분야 추이와 상품을 모으는 중…" /> : null}

      {result ? (
        <>
          <T v="caption">
            {result.fromCache ? `${dateTimeText(result.fetchedAt)}에 모은 결과예요 (저장된 것). 아래로 당기면 새로 모아요.` : `방금 모았어요 · ${dateTimeText(result.fetchedAt)}`}
            {result.data.used.length ? ` · 출처: ${result.data.used.join(', ')}` : ''}
          </T>
          {result.data.notes.map((n) => (
            <Banner key={n} tone="info" icon="info">
              {n}
            </Banner>
          ))}

          {Object.keys(result.data.trend).length ? (
            <Section title={result.demoTrend ? '뜨는 분야 (예시 값)' : '뜨는 분야 — 네이버 클릭 최근 7일 vs 앞 21일'}>
              <Card style={{ gap: 10 }}>
                {trendRows.map((r) => (
                  <View key={r.key} style={{ gap: 4 }}>
                    <Row style={{ justifyContent: 'space-between' }}>
                      <T style={{ fontWeight: '600' }}>{r.name}</T>
                      <T v="caption" color={(r.m ?? 0) > 1.03 ? 'primary' : 'sub'}>
                        {momentumText(r.m)}
                      </T>
                    </Row>
                    <View style={{ height: 8, borderRadius: 4, backgroundColor: t.border, overflow: 'hidden' }}>
                      <View style={{ width: `${Math.max(3, Math.min(100, ((r.m ?? 0) / maxM) * 100))}%`, height: 8, backgroundColor: (r.m ?? 0) > 1.03 ? t.primary : t.faint }} />
                    </View>
                  </View>
                ))}
                {result.demoTrend ? <T v="caption">예시 데이터 모드라 가짜 값이에요. 실제 값은 네이버 API HUB 키를 넣으면 보여요.</T> : null}
              </Card>
            </Section>
          ) : null}
        </>
      ) : null}

      <Section title={`${seasonMonth}월에 많이 찾는 말`}>
        <Card style={{ gap: 8 }}>
          {seasonWords.length ? (
            <>
              <Row gap={8} style={{ flexWrap: 'wrap' }}>
                {seasonWords.map((w) => (
                  <Chip key={w} label={w} onPress={() => router.push({ pathname: '/products', params: { q: w } })} />
                ))}
              </Row>
              <T v="caption">자비스가 계절 가산점에 쓰는 표예요 (실측 순위가 아니라 고르는 눈). 누르면 상품 검색으로 가요.</T>
              {seasonLinks.length ? (
                <>
                  <T v="h3">내 보관함에서 이달의 말에 맞는 상품</T>
                  {seasonLinks.map((l) => (
                    <Row key={l.id} gap={8}>
                      <T style={{ flex: 1 }} numberOfLines={1}>
                        {l.title}
                      </T>
                      <Button small kind="ghost" icon="article" label="글" onPress={() => router.push({ pathname: '/post', params: { linkId: l.id } })} />
                      <Button small kind="ghost" icon="movie" label="영상" onPress={() => router.push({ pathname: '/reel', params: { linkId: l.id } })} />
                    </Row>
                  ))}
                </>
              ) : null}
            </>
          ) : (
            <T v="sub">이달의 말이 없어요.</T>
          )}
        </Card>
      </Section>

      {result ? (
        <Section title={`추천 상품 ${result.data.items.length}개`}>
          {result.data.items.length === 0 ? (
            <Empty icon="search" title="상품 후보가 없어요">
              쿠팡 파트너스 키(또는 네이버 쇼핑 검색 키)를 넣거나 예시 데이터 모드를 켜면 후보가 나와요.
            </Empty>
          ) : (
            result.data.items.map((it) => {
              const busy = busyId === it.id + it.name;
              return (
                <Card key={`${it.source}-${it.id}-${it.name}`} style={{ gap: 8 }}>
                  <Row gap={12} style={{ alignItems: 'flex-start' }}>
                    <Thumb uri={it.image} size={64} />
                    <View style={{ flex: 1, gap: 4, minWidth: 0 }}>
                      <T numberOfLines={2} style={{ fontWeight: '600' }}>
                        {it.name}
                      </T>
                      <Row gap={6} style={{ flexWrap: 'wrap' }}>
                        <Badge label={`점수 ${it.score}`} tone="accent" />
                        {it.price ? <Badge label={won(it.price)} tone="primary" /> : null}
                        <Badge label={it.affiliate === 'coupang' ? '쿠팡 파트너스 링크' : '네이버 · 수수료 링크 아님'} tone={it.affiliate === 'coupang' ? 'info' : 'danger'} />
                      </Row>
                      {it.why.map((w) => (
                        <T key={w} v="caption">
                          {`• ${w}`}
                        </T>
                      ))}
                    </View>
                  </Row>
                  {it.affiliate === 'coupang' ? (
                    <Row style={{ justifyContent: 'space-around', opacity: busy ? 0.5 : 1 }}>
                      <IconButton icon="save" label="보관함 저장" onPress={() => act(it, null)} />
                      <IconButton icon="article" label="블로그 글" onPress={() => act(it, 'post')} />
                      <IconButton icon="movie" label="홍보 영상" onPress={() => act(it, 'reel')} />
                      <IconButton icon="open" label="상품 보기" href={it.url} />
                    </Row>
                  ) : (
                    <Row style={{ justifyContent: 'space-around' }}>
                      <IconButton icon="search" label="쿠팡에서 찾기" onPress={() => router.push({ pathname: '/products', params: { q: it.name.split(' ').slice(0, 3).join(' ') } })} />
                      {it.url ? <IconButton icon="open" label="네이버에서 보기" href={it.url} /> : null}
                    </Row>
                  )}
                </Card>
              );
            })
          )}
        </Section>
      ) : null}
    </Screen>
  );
}
