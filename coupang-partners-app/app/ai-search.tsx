// AI 상품 찾기 — 원하는 제품 → 같은 상품 중 가장 싼 것 → 파트너스 링크 → 보관함 저장 (이름·가격·메모까지).
import { router } from 'expo-router';
import { useState } from 'react';
import { View } from 'react-native';

import type { Product } from '@/src/api/types';
import { aiMemo, coupangSearchUrl, handoffPrompt, parseHandoff, pickCheapest, type CheapestPick } from '@/src/lib/aiSearch';
import { won } from '@/src/lib/format';
import { API_AVAILABLE, IS_DESKTOP } from '@/src/lib/hosting';
import { claudeAgent, type ClaudeJob } from '@/src/api/claudeAgent';
import { ClaudeJobView, useClaudeJob } from '@/src/ui/ClaudeRun';
import { productPageUrl } from '@/src/lib/links';
import { kstToday } from '@/src/lib/productRules';
import { errorText, useApp, type SavedLink } from '@/src/store/AppContext';
import { Badge, Banner, Button, Card, Field, Row, Screen, Section, T, Thumb } from '@/src/ui/kit';
import { PARTNERS_SITE } from '@/src/ui/parts';

export default function AiSearch() {
  const { mode, client, settings, addLink, removeLink, copy, showToast, links } = useApp();
  const [q, setQ] = useState('');
  const [cond, setCond] = useState('');
  const [memo, setMemo] = useState('');
  const [today] = useState(() => kstToday(new Date()));
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [pick, setPick] = useState<(CheapestPick & { q: string }) | null>(null);
  const [savedId, setSavedId] = useState<string | null>(null);
  const [answer, setAnswer] = useState('');
  const [problems, setProblems] = useState<string[]>([]);

  const saved: SavedLink | undefined = savedId ? links.find((l) => l.id === savedId) : undefined;
  const query = q.trim();
  const ready = query.length >= 2;

  /** 쿠팡 API 상품을 파트너스 짧은 링크로 바꿔 저장 */
  const saveProduct = async (p: Product, qq: string) => {
    if (!client) return;
    let url = p.productUrl;
    try {
      const [d] = await client.deeplink([productPageUrl(p.productId)], settings.subId || undefined);
      if (d?.shortenUrl) url = d.shortenUrl;
    } catch (e) {
      showToast(`짧은 링크를 못 만들어 상품에 붙어 온 추적 링크로 저장했어요 (${errorText(e)})`);
    }
    const l = await addLink({
      title: p.productName,
      url,
      originalUrl: productPageUrl(p.productId),
      productId: String(p.productId),
      price: p.productPrice,
      image: p.productImage,
      isRocket: p.isRocket,
      isFreeShipping: p.isFreeShipping,
      category: p.categoryName,
      kind: /여행/.test(p.categoryName ?? '') ? 'travel' : 'shop',
      subId: settings.subId || undefined,
      memo: aiMemo(qq, today, 'api', undefined, memo),
      source: mode === 'demo' ? 'demo' : 'ai',
    });
    setSavedId(l.id);
    return l;
  };

  const runApi = async () => {
    if (!client || !ready || busy) return;
    setBusy(true);
    setErr(null);
    setPick(null);
    setSavedId(null);
    try {
      const sr = await client.searchProducts(query, { subId: settings.subId || undefined, limit: 10, imageSize: '230x230' });
      // 조건 중 앱이 확인할 수 있는 것은 로켓배송뿐이다 (나머지 조건은 Claude에게 맡기기에서 씀)
      const rocketOnly = /로켓/.test(cond);
      const r = pickCheapest(rocketOnly ? sr.products.filter((p) => p.isRocket) : sr.products, query);
      setPick({ ...r, q: query });
      if (!r.chosen) throw new Error('검색 결과에 가격이 있는 상품이 없어요. 검색어를 바꿔 보세요.');
      await saveProduct(r.chosen, query);
      showToast('가장 싼 상품을 보관함에 저장했어요');
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const swap = async (p: Product) => {
    if (busy || !pick) return;
    setBusy(true);
    try {
      if (savedId) await removeLink(savedId);
      await saveProduct(p, pick.q);
      showToast('이 상품으로 바꿔 저장했어요');
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  /** Claude 결과 글 → 검사(파트너스 링크인지) → 보관함. 저장했으면 true */
  const saveFromText = async (text: string, qq: string): Promise<boolean> => {
    setErr(null);
    const r = parseHandoff(text);
    setProblems(r.problems);
    if (!r.item) return false;
    const it = r.item;
    const l = await addLink({
      title: it.name,
      url: it.link,
      originalUrl: it.productUrl,
      productId: it.productId,
      price: it.price,
      isRocket: it.rocket,
      subId: settings.subId || undefined,
      memo: aiMemo(qq || it.name, today, 'claude', it.note, memo),
      source: 'ai',
    });
    setSavedId(l.id);
    showToast('Claude가 찾은 상품을 보관함에 저장했어요');
    return true;
  };

  const saveHandoff = async () => {
    if (await saveFromText(answer, query)) setAnswer('');
  };

  // PC판: 이 PC의 Claude가 작업 브라우저로 직접 찾는다
  const onClaudeDone = (j: ClaudeJob) => {
    if (j.state === 'done' && /"link"/.test(j.result)) saveFromText(j.result, j.q);
  };
  const cj = useClaudeJob(onClaudeDone);
  const runClaude = () => {
    if (!ready || cj.running) return;
    setSavedId(null);
    setProblems([]);
    setPick(null);
    cj.start(query, cond);
  };
  const openPartners = () => claudeAgent.openBrowser('partners').catch((e) => setErr(errorText(e)));

  const apiReady = !!client && (mode === 'api' || mode === 'demo');

  return (
    <Screen>
      <View style={{ gap: 4 }}>
        <T v="title">AI 상품 찾기</T>
        <T v="sub">원하는 제품을 적으면 쿠팡에서 같은 상품 중 가장 싼 것을 찾아, 파트너스 링크로 바꿔 이름·가격·메모까지 보관함에 저장해요.</T>
      </View>

      <Card>
        <Field
          label="찾을 제품"
          value={q}
          onChangeText={setQ}
          placeholder="예: 캡형 물티슈 100매"
          returnKeyType="search"
          onSubmitEditing={() => (apiReady ? runApi() : IS_DESKTOP ? runClaude() : undefined)}
        />
        <Field label="조건 (선택)" value={cond} onChangeText={setCond} placeholder="예: 로켓배송, 10팩 이상" />
        <Field label="보관함 메모에 덧붙일 말 (선택)" value={memo} onChangeText={setMemo} placeholder="예: 겨울 육아템 글에 쓸 것" />
        <T v="caption">&lsquo;가장 싼 것&rsquo;은 검색한 그 순간 쿠팡 화면 기준이에요. 가격은 자주 바뀌고, 글·영상에 &lsquo;최저가&rsquo;라고 쓰지는 않아요.</T>
      </Card>

      <Section title="⚡ 쿠팡 API로 바로 찾기">
        {apiReady ? (
          <Card>
            <T v="sub">
              {`버튼 하나로 끝까지 해요: 쿠팡 검색(최대 10개) → 검색어와 같은 상품만 → 가장 싼 것 → 파트너스 짧은 링크 → 보관함 저장. API 2회를 써요.${mode === 'demo' ? ' (지금은 예시 데이터 모드라 가짜 상품이에요)' : ''}`}
            </T>
            <Button icon="sparkle" label="가장 싼 상품 찾아 저장" onPress={runApi} loading={busy} disabled={!ready || busy} />
            <T v="caption">조건 칸에서는 &lsquo;로켓배송&rsquo;만 앱이 직접 걸러요. 다른 조건은 Claude에게 맡기기에서 써 주세요.</T>
          </Card>
        ) : (
          <Banner tone="info" icon="key" title={API_AVAILABLE ? '쿠팡 API 키가 있어야 해요' : '이 화면에서는 쿠팡 API를 쓸 수 없어요'}>
            {API_AVAILABLE
              ? '파트너스 최종 승인 뒤 API 키를 설정 탭에 넣으면 버튼 하나로 끝까지 해요. 그 전에는 아래 "Claude에게 맡기기"를 써 주세요.'
              : 'Claude 화면은 쿠팡 서버로 요청을 보낼 수 없어요. 아래 "Claude에게 맡기기"를 써 주세요.'}
          </Banner>
        )}
      </Section>

      {IS_DESKTOP ? (
        <Section title="🤖 Claude로 찾기 (이 PC)">
          <Card>
            <T v="sub">검색 버튼을 누르면 이 PC의 Claude가 작업 브라우저 창에서 쿠팡을 낮은가격순으로 검색하고, 같은 상품 중 가장 싼 것을 골라 파트너스 링크까지 만들어 보관함에 저장해요. 보통 2~5분 걸려요.</T>
            <Row gap={8}>
              <Button style={{ flex: 1 }} icon="sparkle" label={cj.running ? 'Claude가 찾는 중…' : 'Claude로 검색해서 저장'} onPress={runClaude} loading={cj.starting} disabled={!ready || cj.running} />
              {cj.running && cj.job ? <Button kind="ghost" icon="stop" label="멈추기" onPress={cj.stop} /> : null}
            </Row>
            <T v="caption">처음 한 번은 설정 → Claude 연결에서 &lsquo;파트너스 로그인 창 열기&rsquo;로 작업 브라우저에 쿠팡 파트너스 로그인을 해 두세요. Claude는 장바구니·구매·결제를 누르지 않고 비밀번호를 넣지 않아요. 내 Claude 사용량을 써요.</T>
          </Card>
          {cj.error ? (
            <Banner
              tone="danger"
              icon="warn"
              title="시작하지 못했어요"
              action={cj.error.code === 'NOT_INSTALLED' ? <Button small kind="ghost" label="설정 열기" onPress={() => router.push('/settings')} /> : undefined}>
              {cj.error.text}
            </Banner>
          ) : null}
          {cj.job ? <ClaudeJobView job={cj.job} onOpenPartners={openPartners} /> : null}
          {cj.job && cj.job.state === 'done' && problems.length ? (
            <Banner tone="danger" icon="warn" title="보관함에 저장하지 않았어요">
              {problems.join('\n')}
            </Banner>
          ) : null}
        </Section>
      ) : null}

      <Section title={IS_DESKTOP ? '다른 Claude 앱으로 하기 (복사·붙여 넣기)' : '🤖 Claude에게 맡기기 (내 컴퓨터 브라우저)'}>
        <Card>
          <T v="sub">Claude가 내 브라우저를 직접 다뤄서 쿠팡을 낮은가격순으로 검색하고, 파트너스 링크를 만들고, 결과를 한 줄로 알려 줘요. 그 줄을 아래에 붙여 넣으면 보관함에 저장돼요.</T>
          <T v="caption">1. 부탁을 복사해 브라우저를 다룰 수 있는 Claude에 붙여 넣어요 — Claude 데스크톱 앱의 새 대화(내장 브라우저) 또는 크롬의 Claude in Chrome. 쿠팡 파트너스 로그인은 그 브라우저에서 미리 직접 해 두세요.</T>
          <Button
            kind="secondary"
            icon="copy"
            label="1. Claude에게 맡길 부탁 복사"
            disabled={!ready}
            onPress={() => copy(handoffPrompt(query, cond, today), '부탁을 복사했어요. Claude 데스크톱 앱(또는 Claude in Chrome)에 붙여 넣으세요')}
          />
          <Field
            label="2. Claude 답의 마지막 줄 붙여 넣기"
            value={answer}
            onChangeText={setAnswer}
            multiline
            style={{ minHeight: 90, fontSize: 13 }}
            placeholder='파트너스매니저 {"name":"…","price":…,"link":"https://link.coupang.com/a/…", …}'
          />
          <Button icon="save" label="검사하고 보관함에 저장" onPress={saveHandoff} disabled={!answer.trim()} />
          {problems.length ? (
            <Banner tone="danger" icon="warn" title="저장하지 않았어요">
              {problems.join('\n')}
            </Banner>
          ) : null}
          <Row gap={8} style={{ flexWrap: 'wrap' }}>
            <Button small kind="ghost" icon="open" label="쿠팡 낮은가격순 열기" href={ready ? coupangSearchUrl(query) : 'https://www.coupang.com/'} />
            <Button small kind="ghost" icon="open" label="쿠팡 파트너스 열기" href={PARTNERS_SITE} />
          </Row>
          <T v="caption">Claude는 장바구니·구매·결제를 누르지 않고, 비밀번호를 넣지 않도록 부탁에 적혀 있어요. Claude가 일하는 동안 화면을 지켜봐 주세요.</T>
        </Card>
      </Section>

      {err ? (
        <Banner tone="danger" icon="warn" title="찾지 못했어요">
          {err}
        </Banner>
      ) : null}

      {saved ? (
        <Section title="보관함에 저장했어요">
          <Card style={{ gap: 8 }}>
            <Row gap={12} style={{ alignItems: 'flex-start' }}>
              <Thumb uri={saved.image} size={64} />
              <View style={{ flex: 1, minWidth: 0, gap: 4 }}>
                <T style={{ fontWeight: '700' }} numberOfLines={2}>
                  {saved.title}
                </T>
                <Row gap={6} style={{ flexWrap: 'wrap' }}>
                  {saved.price ? <Badge label={won(saved.price)} tone="primary" /> : <Badge label="가격 모름" tone="danger" />}
                  {saved.isRocket ? <Badge label="로켓배송" tone="info" /> : null}
                </Row>
                <T v="caption" color="info" numberOfLines={1} selectable>
                  {saved.url}
                </T>
                {saved.memo ? <T v="caption">{saved.memo}</T> : null}
              </View>
            </Row>
            <Row gap={8} style={{ flexWrap: 'wrap' }}>
              <Button small kind="secondary" icon="write" label="짧은 글" onPress={() => router.push({ pathname: '/compose', params: { linkId: saved.id } })} />
              <Button small kind="secondary" icon="article" label="블로그 글" onPress={() => router.push({ pathname: '/post', params: { linkId: saved.id } })} />
              <Button small kind="secondary" icon="movie" label="홍보 영상" onPress={() => router.push({ pathname: '/reel', params: { linkId: saved.id } })} />
              <Button small kind="ghost" icon="link" label="보관함" onPress={() => router.push('/links')} />
            </Row>
          </Card>
        </Section>
      ) : null}

      {pick && pick.ranked.length > 1 ? (
        <Section title={pick.loose ? '검색어와 딱 맞는 상품이 없어 전체에서 골랐어요' : `같은 상품 ${pick.ranked.length}개 (싼 순)`}>
          {pick.ranked.slice(0, 8).map((p, i) => (
            <Card key={`${p.productId}`} style={{ gap: 6 }}>
              <Row gap={10} style={{ alignItems: 'center' }}>
                <Thumb uri={p.productImage} size={44} />
                <View style={{ flex: 1, minWidth: 0 }}>
                  <T numberOfLines={2}>{p.productName}</T>
                  <T v="caption">{`${won(p.productPrice)}${p.isRocket ? ' · 로켓배송' : ''}${i === 0 ? ' · 가장 쌈' : ''}`}</T>
                </View>
                {saved?.productId === String(p.productId) ? (
                  <Badge label="저장됨" tone="primary" />
                ) : (
                  <Button small kind="ghost" label="이걸로 바꾸기" onPress={() => swap(p)} disabled={busy} />
                )}
              </Row>
            </Card>
          ))}
          <T v="caption">쿠팡 API 검색은 한 번에 10개까지만 받아요. 더 싼 상품이 검색 결과 뒤쪽에 있을 수 있어요.</T>
        </Section>
      ) : null}
    </Screen>
  );
}
