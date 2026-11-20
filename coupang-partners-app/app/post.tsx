// 블로그 소개 글 — 자비스 '블로그 자동발행기'의 🛒 상품 소개 글과 같은 방식 (src/lib/productPost.ts).
import { useLocalSearchParams } from 'expo-router';
import { useMemo, useState } from 'react';
import { View } from 'react-native';

import { hasDisclosureOnTop } from '@/src/lib/compose';
import { assemblePost, checkPost, dropBad, fixPrompt, parsePost, postPrompt, type PostDraft } from '@/src/lib/productPost';
import { adNotice, kstToday } from '@/src/lib/productRules';
import { useApp } from '@/src/store/AppContext';
import { AiWriter, type AiResult } from '@/src/ui/AiWriter';
import { Banner, Button, Card, Field, Row, Screen, Section, T } from '@/src/ui/kit';
import { pickFromLink, ProductInput, toInfo, type PickState } from '@/src/ui/productInput';

interface Done {
  /** 이 글을 만든 상품·메모 (바뀌면 결과를 지운다) */
  key: string;
  draft: PostDraft;
  removed: string[];
  warns: string[];
  rounds: number;
}

export default function Post() {
  const params = useLocalSearchParams<{ linkId?: string }>();
  const { links, settings, copy, updateLink, checklist, toggleChecklist } = useApp();
  const [pick, setPick] = useState<PickState>(() => pickFromLink(links.find((l) => l.id === params.linkId) ?? links[0]));
  const [today] = useState(() => kstToday(new Date()));
  const [done, setDone] = useState<Done | null>(null);
  const [edit, setEdit] = useState<{ from: string; text: string } | null>(null);

  const info = useMemo(() => toInfo(pick, links), [pick, links]);
  const pen = settings.penName;
  const key = info ? JSON.stringify([info, pen]) : '';
  const prompt = useMemo(() => (info ? postPrompt(info, today, pen) : ''), [info, today, pen]);
  const result = done && done.key === key ? done : null;

  const final = useMemo(() => (result && info ? assemblePost(result.draft, info, today, { disclosure: settings.disclosure, pen }) : null), [result, info, today, settings.disclosure, pen]);
  const body = final ? (edit && edit.from === final.body ? edit.text : final.body) : '';
  const notice = info ? adNotice(info, settings.disclosure) : '';
  const okTop = !final || hasDisclosureOnTop(body, notice.replace(/^\[광고\]\s*/, ''));

  const errorsOf = (d: PostDraft) => (info ? checkPost(d, info, today).filter((i) => i.severity === 'error').map((i) => (i.block === 0 ? `제목: ${i.msg}` : i.block ? `${i.block}번 블록: ${i.msg}` : i.msg)) : []);

  const finish = (r: AiResult<PostDraft>) => {
    if (!info) return;
    // 한 번 고쳐도 남은 것은 그 문장·표 줄을 뺀다 (지어내지 않는다)
    const cleaned = r.errors.length ? dropBad(r.draft, info, today) : { draft: r.draft, removed: [] };
    const warns = checkPost(cleaned.draft, info, today)
      .filter((i) => i.severity === 'warn')
      .map((i) => i.msg);
    setDone({ key, draft: cleaned.draft, removed: cleaned.removed, warns, rounds: r.rounds });
    setEdit(null);
    // 메모·종류는 보관함 링크에 남겨 둔다 (다음에 영상 만들 때도 쓰게)
    if (pick.linkId) updateLink(pick.linkId, { memo: pick.memo.trim() || undefined, kind: pick.kind });
  };

  return (
    <Screen>
      <View style={{ gap: 4 }}>
        <T v="title">블로그 소개 글 (자비스 방식)</T>
        <T v="sub">
          자비스 블로그 자동발행기의 상품 소개 글과 같은 규칙이에요. 상품 정보·메모에 있는 사실만 쓰고, 써 본 척하지 않아요. 광고 고지(맨 위)·구매 링크(표 뒤·글 끝)·가격 기준일은 앱이 붙여요.
        </T>
      </View>

      <ProductInput value={pick} onChange={setPick} links={links} />

      {!info ? (
        <Banner tone="info" icon="info">
          상품을 고르거나 이름을 적어 주세요.
        </Banner>
      ) : (
        <AiWriter what="블로그 글" prompt={prompt} parse={parsePost} check={errorsOf} fixPrompt={(d, errs) => fixPrompt(d, errs.map((m) => ({ rule: 'x', severity: 'error', msg: m })))} onDone={finish} />
      )}

      {result && final ? (
        <Section title="완성 글">
          {result.removed.length ? (
            <Banner tone="warn" icon="warn" title={`근거가 없는 ${result.removed.length}곳을 뺐어요`}>
              {result.removed.slice(0, 6).join('\n')}
            </Banner>
          ) : (
            <Banner tone="success" icon="checked">
              {result.rounds === 2 ? '한 번 고쳐서 검사를 통과했어요.' : '검사를 통과했어요 (없는 숫자·경험담·과장 표현 없음).'}
            </Banner>
          )}
          {result.warns.length ? <T v="caption">{`글 모양 참고: ${result.warns.join(' · ')}`}</T> : null}

          <Card style={{ gap: 6 }}>
            <T v="caption">제목</T>
            <T v="h3" selectable>
              {final.title}
            </T>
            <Button small kind="secondary" icon="copy" label="제목 복사" onPress={() => copy(final.title, '제목을 복사했어요')} />
          </Card>

          {okTop ? null : (
            <Banner tone="warn" icon="warn" title="광고 고지가 맨 앞에 없어요">
              공정위 지침상 고지는 글의 첫 부분에 있어야 해요. 고친 글을 되돌리려면 아래 &lsquo;처음 글로&rsquo;를 눌러 주세요.
            </Banner>
          )}
          <Field value={body} onChangeText={(v) => setEdit({ from: final.body, text: v })} multiline style={{ minHeight: 320, fontSize: 14, lineHeight: 21 }} />
          <Row>
            <Button
              style={{ flex: 1 }}
              icon="copy"
              label="본문 복사"
              disabled={!okTop}
              onPress={() => {
                copy(body, '본문을 복사했어요. 네이버 블로그 글쓰기에 붙여 넣으세요');
                if (!checklist.done.post) toggleChecklist('post');
              }}
            />
            {edit ? <Button style={{ flex: 1 }} kind="ghost" icon="refresh" label="처음 글로" onPress={() => setEdit(null)} /> : null}
          </Row>
          <T v="caption">
            {`해시태그 ${final.hashtags.length}개는 글 끝에 들어 있어요. 사진은 블로그에서 직접 넣어 주세요 (첫 사진이 대표 사진이 돼요). 가격은 ${today.month}월 ${today.day}일 기준이라 올리기 직전에 한 번 더 확인해 주세요.`}
          </T>
        </Section>
      ) : null}
    </Screen>
  );
}
