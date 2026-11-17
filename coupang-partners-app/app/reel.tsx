// 제품 홍보 영상(릴스·쇼츠) — 자비스 🛒 제품 릴스와 같은 대본 규칙·화면 배치 (src/lib/productReel.ts, src/video/reelDraw.ts).
import { useLocalSearchParams } from 'expo-router';
import { useMemo, useState } from 'react';
import { View } from 'react-native';

import { buildMeta, HOOKS, lintReel, parseReel, priceLine, reelFixPrompt, reelPrompt, reelScenes, SHORT_NOTICE, type ReelScript } from '@/src/lib/productReel';
import { kstToday } from '@/src/lib/productRules';
import { useApp } from '@/src/store/AppContext';
import { AiWriter, type AiResult } from '@/src/ui/AiWriter';
import { Banner, Button, Card, Field, Row, Screen, Section, T } from '@/src/ui/kit';
import { pickFromLink, ProductInput, toInfo, type PickState } from '@/src/ui/productInput';
import { ReelStudio } from '@/src/video/ReelStudio';
import type { ReelPlan } from '@/src/video/reelDraw';

function CopyBlock({ label, text }: { label: string; text: string }) {
  const { copy } = useApp();
  return (
    <Card style={{ gap: 6 }}>
      <Row style={{ justifyContent: 'space-between' }}>
        <T v="h3">{label}</T>
        <Button small kind="secondary" icon="copy" label="복사" onPress={() => copy(text, `${label}을(를) 복사했어요`)} />
      </Row>
      <T v="sub" selectable>
        {text}
      </T>
    </Card>
  );
}

export default function Reel() {
  const params = useLocalSearchParams<{ linkId?: string }>();
  const { links, settings, updateLink } = useApp();
  const [pick, setPick] = useState<PickState>(() => pickFromLink(links.find((l) => l.id === params.linkId) ?? links[0]));
  const [today] = useState(() => kstToday(new Date()));
  const [script, setScript] = useState<{ key: string; sc: ReelScript; rounds: number } | null>(null);

  const info = useMemo(() => toInfo(pick, links), [pick, links]);
  const link = pick.linkId ? links.find((l) => l.id === pick.linkId) : undefined;
  const key = info ? JSON.stringify(info) : '';
  const prompt = useMemo(() => (info ? reelPrompt(info, today) : ''), [info, today]);
  const cur = script && script.key === key ? script : null;
  const lint = useMemo(() => (cur && info ? lintReel(cur.sc, info, today) : null), [cur, info, today]);

  const errorsOf = (sc: ReelScript) => (info ? lintReel(sc, info, today).errors : []);
  const finish = (r: AiResult<ReelScript>) => {
    setScript({ key, sc: r.draft, rounds: r.rounds });
    if (pick.linkId) updateLink(pick.linkId, { memo: pick.memo.trim() || undefined, kind: pick.kind });
  };
  const edit = (patch: Partial<ReelScript>) => cur && setScript({ ...cur, sc: { ...cur.sc, ...patch } });

  const siteLabel = settings.sites[0]?.label;
  const plan: ReelPlan | null = useMemo(
    () =>
      cur && info
        ? { scenes: reelScenes(cur.sc, settings.reelCta), notice: SHORT_NOTICE[info.affiliate], priceLine: priceLine(info, today), productName: info.name }
        : null,
    [cur, info, settings.reelCta, today],
  );
  const meta = useMemo(
    () => (cur && info ? buildMeta(cur.sc, info, today, { disclosure: settings.disclosure, cta: settings.reelCta, siteLabel }) : null),
    [cur, info, today, settings.disclosure, settings.reelCta, siteLabel],
  );
  const voice = cur ? [cur.sc.hookSay, ...cur.sc.cuts.map((c) => c.say)].filter(Boolean).join('\n') : '';
  const hook = cur ? HOOKS.find((h) => h.id === cur.sc.hookId) : undefined;
  const ok = !!lint && lint.errors.length === 0;

  return (
    <Screen>
      <View style={{ gap: 4 }}>
        <T v="title">홍보 영상 (자비스 방식)</T>
        <T v="sub">
          자비스 제품 릴스와 같은 구조예요: 0초부터 제품 + 3초 훅 → 컷마다 흰 박스 한 구절 + 노란 강조 → 끝은 구매 안내. 모든 장면 왼쪽 위에 &lsquo;광고&rsquo;와 수수료 고지가 붙고, 숫자는 상품 정보·메모에 있는 것만 써요.
        </T>
      </View>

      <ProductInput value={pick} onChange={setPick} links={links} />

      {info ? (
        <AiWriter what="영상 대본" prompt={prompt} parse={parseReel} check={errorsOf} fixPrompt={(_d, errs) => reelFixPrompt(info, today, errs)} onDone={finish} />
      ) : (
        <Banner tone="info" icon="info">
          상품을 고르거나 이름을 적어 주세요.
        </Banner>
      )}

      {cur && lint ? (
        <Section title="대본 (고칠 수 있어요)">
          {lint.errors.length ? (
            <Banner tone="danger" icon="warn" title="검사를 통과하지 못해 영상을 만들지 않아요">
              {`${lint.errors.slice(0, 5).join('\n')}\n아래에서 직접 고치면 바로 다시 검사해요.`}
            </Banner>
          ) : (
            <Banner tone="success" icon="checked">
              {`검사 통과${cur.rounds === 2 ? ' (한 번 고침)' : ''} — 없는 숫자·써 본 척·과장 표현 없음.${hook ? ` 훅: ${hook.name}` : ''}`}
            </Banner>
          )}
          {lint.warns.length ? <T v="caption">{`참고: ${lint.warns.join(' · ')}`}</T> : null}
          <Card style={{ gap: 8 }}>
            <Field label="첫 화면 (2줄, 줄마다 15자 이하)" value={cur.sc.hook} onChangeText={(v) => edit({ hook: v })} multiline style={{ minHeight: 70 }} />
            {cur.sc.cuts.map((c, i) => (
              <Row key={i} gap={8} style={{ alignItems: 'flex-end' }}>
                <View style={{ flex: 2, minWidth: 0 }}>
                  <Field
                    label={`${i + 1}번 컷 글자`}
                    value={c.text}
                    onChangeText={(v) => edit({ cuts: cur.sc.cuts.map((x, j) => (j === i ? { ...x, text: v } : x)) })}
                  />
                </View>
                <View style={{ flex: 1, minWidth: 0 }}>
                  <Field label="노란 강조" value={c.badge} onChangeText={(v) => edit({ cuts: cur.sc.cuts.map((x, j) => (j === i ? { ...x, badge: v } : x)) })} />
                </View>
              </Row>
            ))}
          </Card>
        </Section>
      ) : null}

      {plan && cur ? (
        <Section title="영상 만들기">
          <ReelStudio plan={plan} fileBase={`reel-${today.iso}`} imageUrl={link?.image} disabled={!ok} />
        </Section>
      ) : null}

      {meta && cur && ok ? (
        <Section title="올릴 때 쓸 글">
          <CopyBlock label="유튜브 쇼츠 제목" text={meta.title} />
          <CopyBlock label="인스타 캡션" text={meta.instagramCaption} />
          <CopyBlock label="유튜브 설명" text={meta.youtubeDescription} />
          {voice ? <CopyBlock label="목소리 대본 (직접 녹음·편집 앱 음성용)" text={voice} /> : null}
          <T v="caption">광고 표시와 수수료 문구는 캡션·설명 첫 줄에 있어요. 인스타 캡션의 링크는 눌리지 않아서 넣지 않았어요 — 끝 안내(댓글 DM 또는 프로필 링크)를 따라 주세요. 해시태그는 인스타 규칙에 맞춰 5개까지예요.</T>
        </Section>
      ) : null}
    </Screen>
  );
}
