import { router } from 'expo-router';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { ScrollView, View } from 'react-native';

import type { ReportBundle } from '@/src/api/types';
import { longYmd, presetRange, RANGE_PRESETS, shortYmd, type RangePreset } from '@/src/lib/dates';
import { num, pct, won } from '@/src/lib/format';
import { summarize } from '@/src/lib/reports';
import { errorText, useApp, type Cached } from '@/src/store/AppContext';
import { Banner, Bars, Button, Card, Chip, Loading, Row, Screen, Section, Stat, T } from '@/src/ui/kit';
import { CacheNote, ModeBanner, openUrl, PARTNERS_SITE, UsageMeter } from '@/src/ui/parts';
import { useTheme } from '@/src/ui/theme';

const HOUR = 60 * 60 * 1000;

export default function Reports() {
  const { mode, cached, settings, updateSettings } = useApp();
  const t = useTheme();
  const [preset, setPreset] = useState<RangePreset>('last7');
  const [subId, setSubId] = useState<string>(settings.subId);
  const [metric, setMetric] = useState<'clicks' | 'commission' | 'orders'>('commission');
  // 결과·오류에 조회 조건을 붙여 두고, 지금 조건과 같을 때만 보여 준다.
  const key = `${mode}.${preset}.${subId || 'all'}`;
  const [stored, setStored] = useState<(Cached<ReportBundle> & { key: string }) | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [err, setErr] = useState<{ key: string; text: string } | null>(null);
  const res = stored && stored.key === key ? stored : null;
  const error = err && err.key === key ? err.text : null;
  const loading = mode !== 'manual' && !res && !error;

  const channels = useMemo(() => [...new Set([settings.subId, ...settings.channels].filter(Boolean))], [settings]);
  const range = useMemo(() => presetRange(preset, res ? new Date(res.fetchedAt) : new Date()), [preset, res]);

  // 리포트 4종을 불러온다 (1시간 안의 같은 조건은 저장된 값을 써서 호출하지 않음). 상태는 부르는 쪽에서 바꾼다.
  const fetchReports = useCallback(
    (p: RangePreset, s: string, force = false) =>
      cached(`reports.${p}.${s || 'all'}`, HOUR, (c) => c.reports(presetRange(p, new Date()), s || undefined), force),
    [cached],
  );

  // 화면을 열거나 기간·채널·모드가 바뀌면 불러온다. 늦게 온 이전 조건의 응답은 버린다.
  useEffect(() => {
    if (mode === 'manual') return;
    let current = true;
    fetchReports(preset, subId).then(
      (got) => {
        if (!current) return;
        setStored({ ...got, key });
        setErr(null);
      },
      (e) => current && setErr({ key, text: errorText(e) }),
    );
    return () => {
      current = false;
    };
  }, [mode, preset, subId, key, fetchReports]);

  const refresh = async () => {
    setRefreshing(true);
    setErr(null);
    try {
      setStored({ ...(await fetchReports(preset, subId, true)), key });
    } catch (e) {
      setErr({ key, text: errorText(e) });
    } finally {
      setRefreshing(false);
    }
  };

  const s = useMemo(() => (res ? summarize(res.data, range) : null), [res, range]);

  if (mode === 'manual') {
    return (
      <Screen>
        <Banner tone="info" icon="chart" title="실적 조회는 API 키가 있어야 해요">
          그 전까지는 파트너스 사이트의 리포트 메뉴에서 클릭·주문·수익을 확인하세요. API 키를 넣으면 여기서 기간별 수익, 전환율, 잘 팔린 상품, 채널별 성과를 볼 수 있어요.
        </Banner>
        <Button label="파트너스 사이트 열기" icon="open" onPress={() => openUrl(PARTNERS_SITE)} />
        <Button label="API 키 넣기" icon="key" kind="secondary" onPress={() => router.push('/settings')} />
        <Button label="예시 데이터로 화면 둘러보기" icon="eye" kind="ghost" onPress={() => updateSettings({ demoMode: true })} />
      </Screen>
    );
  }

  const series = s
    ? s.days.map((d) => ({ label: shortYmd(d.date), value: metric === 'clicks' ? d.clicks : metric === 'orders' ? d.orders : d.commission }))
    : [];

  return (
    <Screen refreshing={refreshing} onRefresh={refresh}>
      <ModeBanner compact />
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 8 }}>
        {RANGE_PRESETS.map((p) => (
          <Chip key={p.key} label={p.label} selected={preset === p.key} onPress={() => setPreset(p.key)} />
        ))}
      </ScrollView>
      {channels.length > 0 ? (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 8 }}>
          <Chip label="전체 채널" selected={!subId} onPress={() => setSubId('')} />
          {channels.map((c) => (
            <Chip key={c} label={c} selected={subId === c} onPress={() => setSubId(c)} />
          ))}
        </ScrollView>
      ) : null}

      <T v="caption">
        {longYmd(range.startDate)} ~ {longYmd(range.endDate)} · 실적은 매일 오후 3시쯤 전날 것까지 반영돼요.
      </T>

      {error ? (
        <Banner tone="danger" icon="warn" title="불러오지 못했어요" action={<Button small kind="ghost" label="다시 시도" onPress={refresh} />}>
          {error}
        </Banner>
      ) : null}
      {loading ? <Loading label="리포트 4종을 차례로 불러오는 중…" /> : null}

      {s && res ? (
        <>
          <CacheNote fetchedAt={res.fetchedAt} fromCache={res.fromCache} />
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10 }}>
            <Stat label="수익" value={won(s.commission)} tone="accent" sub="확정 전 금액" />
            <Stat label="판매액" value={won(s.gmv)} sub={`${num(s.quantity)}개`} />
            <Stat label="클릭" value={num(s.clicks)} />
            <Stat label="주문" value={`${num(s.orders)}건`} sub={s.cancels ? `취소 ${num(s.cancels)}건` : undefined} />
            <Stat label="전환율" value={pct(s.conversion, 2)} sub="주문 ÷ 클릭" />
            <Stat label="클릭당 수익" value={won(s.epc)} sub="수익 ÷ 클릭" />
          </View>

          <Card>
            <Row style={{ justifyContent: 'space-between' }}>
              <T v="h3">날짜별</T>
              <Row gap={6}>
                <Chip label="수익" selected={metric === 'commission'} onPress={() => setMetric('commission')} />
                <Chip label="클릭" selected={metric === 'clicks'} onPress={() => setMetric('clicks')} />
                <Chip label="주문" selected={metric === 'orders'} onPress={() => setMetric('orders')} />
              </Row>
            </Row>
            <Bars data={series} color={metric === 'commission' ? t.bar2 : t.bar} format={metric === 'commission' ? won : (n) => num(n)} />
          </Card>

          <Section title="잘 팔린 상품">
            <Card style={{ gap: 0 }}>
              {s.topProducts.length === 0 ? (
                <T v="sub">이 기간에는 주문이 없어요.</T>
              ) : (
                s.topProducts.map((p, i) => (
                  <Row key={p.key} gap={10} style={{ paddingVertical: 10, borderTopWidth: i ? 1 : 0, borderTopColor: t.border }}>
                    <T v="h3" color="faint" style={{ width: 18 }}>
                      {String(i + 1)}
                    </T>
                    <View style={{ flex: 1, gap: 2 }}>
                      <T numberOfLines={1} style={{ fontWeight: '600' }}>
                        {p.name}
                      </T>
                      <T v="caption">
                        주문 {p.orders}건 · {num(p.quantity)}개 · 판매액 {won(p.gmv)}
                      </T>
                    </View>
                    <T v="h3" color="accent">
                      {won(p.commission)}
                    </T>
                  </Row>
                ))
              )}
            </Card>
            {s.topProducts.length ? <T v="caption">잘 팔린 상품은 다른 채널이나 다른 글에서 한 번 더 소개해 보세요.</T> : null}
          </Section>

          {!subId && s.channels.length > 1 ? (
            <Section title="채널별">
              <Card style={{ gap: 0 }}>
                {s.channels.map((c, i) => (
                  <Row key={c.subId} gap={10} style={{ paddingVertical: 10, borderTopWidth: i ? 1 : 0, borderTopColor: t.border }}>
                    <View style={{ flex: 1, gap: 2 }}>
                      <T style={{ fontWeight: '600' }}>{c.subId}</T>
                      <T v="caption">
                        클릭 {num(c.clicks)} · 주문 {num(c.orders)}건 · 전환율 {pct(c.clicks ? c.orders / c.clicks : 0, 2)}
                      </T>
                    </View>
                    <T v="h3" color="accent">
                      {won(c.commission)}
                    </T>
                  </Row>
                ))}
              </Card>
            </Section>
          ) : null}
        </>
      ) : null}

      <Card>
        <UsageMeter />
        <T v="caption">새로 불러올 때마다 API를 4회 이상 써요 (클릭·주문·취소·수익). 같은 조건은 1시간 동안 저장된 값을 보여 줘요. 수익은 취소·반품에 따라 바뀔 수 있고, 최종 정산 금액은 파트너스 사이트가 기준이에요.</T>
      </Card>
    </Screen>
  );
}
