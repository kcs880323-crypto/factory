import { router, useFocusEffect, type Href } from 'expo-router';
import { useCallback, useState } from 'react';
import { Pressable, View } from 'react-native';

import { presetRange } from '@/src/lib/dates';
import { dateTimeText, num, won } from '@/src/lib/format';
import { summarize, type ReportSummary } from '@/src/lib/reports';
import type { ReportBundle } from '@/src/api/types';
import { useApp } from '@/src/store/AppContext';
import { Button, Card, Icon, Row, Section, Stat, T, type IconName } from '@/src/ui/kit';
import { LinkCard, ModeBanner } from '@/src/ui/parts';
import { useTheme } from '@/src/ui/theme';
import { Screen } from '@/src/ui/kit';

const ACTIONS: { label: string; desc: string; icon: IconName; href: Href }[] = [
  { label: 'AI 상품 찾기', desc: '가장 싼 것 → 링크 → 보관함', icon: 'sparkle', href: '/ai-search' },
  { label: '네이버 상품 추천', desc: '뜨는 분야·이달의 말', icon: 'trend', href: '/recommend' },
  { label: '상품 찾기', desc: '검색·골드박스·베스트', icon: 'search', href: '/products' },
  { label: '블로그 소개 글', desc: '자비스 방식 · AI', icon: 'article', href: '/post' },
  { label: '홍보 영상', desc: '릴스·쇼츠 세로 영상', icon: 'movie', href: '/reel' },
  { label: '링크 저장·변환', desc: '쿠팡 주소 붙여넣기', icon: 'link', href: '/links' },
  { label: '짧은 홍보 글', desc: '인스타·카톡·스레드', icon: 'write', href: '/compose' },
  { label: '실적 보기', desc: '클릭·주문·수익', icon: 'chart', href: '/reports' },
];

export default function Home() {
  const { mode, links, checklist, toggleChecklist, peekCache, settings } = useApp();
  const t = useTheme();
  const [week, setWeek] = useState<{ s: ReportSummary; at: number } | null>(null);

  // 실적 탭에서 불러온 최근 7일 결과가 있으면 보여 준다 (홈에서는 API를 부르지 않음).
  useFocusEffect(
    useCallback(() => {
      if (mode === 'manual') {
        setWeek(null);
        return;
      }
      const key = `reports.last7.${settings.subId || 'all'}`;
      peekCache<ReportBundle>(key).then((hit) => {
        setWeek(hit ? { s: summarize(hit.data, presetRange('last7', new Date(hit.fetchedAt))), at: hit.fetchedAt } : null);
      });
    }, [mode, peekCache, settings.subId]),
  );

  const tasks = [
    { id: 'link', label: '새 상품 링크 1개 저장하기' },
    { id: 'post', label: '홍보 글 1개 올리기' },
    { id: 'disclosure', label: '올린 글 맨 위에 대가성 문구가 있는지 확인' },
    {
      id: 'report',
      label: mode === 'manual' ? '파트너스 사이트에서 어제 실적 확인' : '실적 보고 잘 팔린 상품 다시 소개하기',
    },
  ];
  const doneCount = tasks.filter((x) => checklist.done[x.id]).length;

  return (
    <Screen>
      <View style={{ gap: 4 }}>
        <T v="title">오늘도 링크 하나, 글 하나</T>
        <T v="sub">쿠팡 파트너스 활동을 휴대폰에서 정리하는 개인용 도구예요.</T>
      </View>

      <ModeBanner />

      {links.length === 0 && mode === 'manual' ? (
        <Card>
          <Row>
            <Icon name="idea" color={t.accent} />
            <T v="h3">처음이라면 이 순서대로</T>
          </Row>
          <T v="sub">1. 가이드를 보고 쿠팡 파트너스에 가입해요.</T>
          <T v="sub">2. 파트너스 사이트에서 상품 링크를 만들어 링크 탭에 붙여 넣어요.</T>
          <T v="sub">3. 홍보 글을 만들어 블로그·SNS에 올려요. 대가성 문구는 앱이 맨 앞에 넣어 줘요.</T>
          <Button label="가입·운영 가이드 보기" icon="book" kind="secondary" onPress={() => router.push('/guide')} />
        </Card>
      ) : null}

      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10 }}>
        {ACTIONS.map((a) => (
          <Pressable
            key={a.label}
            onPress={() => router.push(a.href)}
            accessibilityRole="button"
            style={({ pressed }) => ({
              flexGrow: 1,
              flexBasis: '45%',
              backgroundColor: t.card,
              borderRadius: 16,
              borderWidth: 1,
              borderColor: t.border,
              padding: 14,
              gap: 6,
              opacity: pressed ? 0.7 : 1,
            })}>
            <View style={{ width: 36, height: 36, borderRadius: 10, backgroundColor: t.primarySoft, alignItems: 'center', justifyContent: 'center' }}>
              <Icon name={a.icon} color={t.primary} />
            </View>
            <T v="h3">{a.label}</T>
            <T v="caption">{a.desc}</T>
          </Pressable>
        ))}
      </View>

      {settings.sites.length ? (
        <Section title="내 채널">
          <Card style={{ gap: 8 }}>
            {settings.sites.map((x) => (
              <Button key={x.url} kind="secondary" icon="open" label={`${x.label} 열기`} href={x.url} />
            ))}
            <T v="caption">추천 페이지에 상품을 올리는 일은 자비스(🛒 → 🌐 추천 페이지에 올리기)에서 해요. 링크는 링크 탭의 &lsquo;자비스로 보내기&rsquo;로 넘겨요.</T>
          </Card>
        </Section>
      ) : null}

      {mode !== 'manual' ? (
        <Section
          title="최근 7일"
          right={<Button small kind="ghost" label="실적 탭" onPress={() => router.push('/reports')} />}>
          {week ? (
            <>
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10 }}>
                <Stat label="수익" value={won(week.s.commission)} tone="accent" />
                <Stat label="클릭" value={num(week.s.clicks)} />
                <Stat label="주문" value={`${num(week.s.orders)}건`} />
                <Stat label="판매액" value={won(week.s.gmv)} />
              </View>
              <T v="caption">{dateTimeText(week.at)}에 실적 탭에서 불러온 값이에요.</T>
            </>
          ) : (
            <Card>
              <T v="sub">아직 불러온 실적이 없어요. 실적 탭에서 &lsquo;최근 7일&rsquo;을 한 번 열면 여기에 요약이 보여요.</T>
            </Card>
          )}
        </Section>
      ) : null}

      <Section title="오늘의 할 일" right={<T v="caption">{`${doneCount}/${tasks.length}`}</T>}>
        <Card style={{ gap: 4 }}>
          {tasks.map((task) => {
            const done = !!checklist.done[task.id];
            return (
              <Pressable
                key={task.id}
                onPress={() => toggleChecklist(task.id)}
                accessibilityRole="checkbox"
                accessibilityState={{ checked: done }}
                style={{ flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 8 }}>
                <Icon name={done ? 'checked' : 'unchecked'} color={done ? t.primary : t.faint} />
                <T style={{ flex: 1, textDecorationLine: done ? 'line-through' : 'none' }} color={done ? 'faint' : undefined}>
                  {task.label}
                </T>
              </Pressable>
            );
          })}
        </Card>
      </Section>

      <Section
        title="최근 저장한 링크"
        right={links.length > 3 ? <Button small kind="ghost" label="전체 보기" onPress={() => router.push('/links')} /> : null}>
        {links.length === 0 ? (
          <Card>
            <T v="sub">아직 저장한 링크가 없어요. 링크 탭에서 첫 링크를 저장해 보세요.</T>
          </Card>
        ) : (
          links.slice(0, 3).map((l) => <LinkCard key={l.id} link={l} />)
        )}
      </Section>

      <Button label="가입·운영 가이드" icon="book" kind="ghost" onPress={() => router.push('/guide')} />
    </Screen>
  );
}
