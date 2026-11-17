import { useLocalSearchParams } from 'expo-router';
import { useMemo, useState } from 'react';
import { ScrollView, View } from 'react-native';

import { CHANNELS, composePost, ensureDisclosureOnTop, hasDisclosureOnTop, type Channel } from '@/src/lib/compose';
import { won } from '@/src/lib/format';
import { useApp } from '@/src/store/AppContext';
import { Banner, Button, Card, Chip, Field, Row, Screen, T } from '@/src/ui/kit';

export default function Compose() {
  const params = useLocalSearchParams<{ linkId?: string }>();
  const { links, settings, copy, share, checklist, toggleChecklist } = useApp();
  const [linkId, setLinkId] = useState<string | undefined>(params.linkId ?? links[0]?.id);
  const [channel, setChannel] = useState<Channel>('blog');
  const [manualTitle, setManualTitle] = useState('');
  const [manualUrl, setManualUrl] = useState('');
  // 사용자가 고친 글. 링크·채널이 바뀌어 새 초안이 나오면 버린다.
  const [edit, setEdit] = useState<{ from: string; text: string } | null>(null);

  const link = useMemo(() => links.find((l) => l.id === linkId), [links, linkId]);
  const disclosure = settings.disclosure;
  const profileSite = settings.sites[0];

  const draft = useMemo(() => {
    const input = link
      ? { title: link.title, url: link.url, price: link.price, memo: link.memo, isRocket: link.isRocket }
      : { title: manualTitle, url: manualUrl || '(링크를 여기에)' };
    return composePost(channel, { ...input, disclosure, profileLabel: profileSite?.label });
  }, [link, channel, manualTitle, manualUrl, disclosure, profileSite]);
  const text = edit && edit.from === draft ? edit.text : draft;
  const setText = (v: string) => setEdit({ from: draft, text: v });

  const okTop = hasDisclosureOnTop(text, disclosure);
  const hint = CHANNELS.find((c) => c.key === channel)?.hint;

  const markPosted = () => {
    if (!checklist.done.post) toggleChecklist('post');
  };

  return (
    <Screen>
      <View style={{ gap: 6 }}>
        <T v="h3">어떤 링크로 쓸까요?</T>
        {links.length ? (
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 8 }}>
            {links.slice(0, 30).map((l) => (
              <Chip key={l.id} label={l.title.length > 18 ? `${l.title.slice(0, 17)}…` : l.title} selected={l.id === linkId} onPress={() => setLinkId(l.id)} />
            ))}
            <Chip label="직접 입력" selected={!link} onPress={() => setLinkId(undefined)} />
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
          {link.price ? <T v="caption">{won(link.price)}</T> : null}
        </Card>
      ) : (
        <Card>
          <Field label="상품 이름" value={manualTitle} onChangeText={setManualTitle} placeholder="예: 캡형 물티슈 100매 10팩" />
          <Field label="파트너스 링크" value={manualUrl} onChangeText={setManualUrl} placeholder="https://link.coupang.com/a/…" autoCapitalize="none" keyboardType="url" />
        </Card>
      )}

      <View style={{ gap: 6 }}>
        <T v="h3">어디에 올릴까요?</T>
        <Row gap={8} style={{ flexWrap: 'wrap' }}>
          {CHANNELS.map((c) => (
            <Chip key={c.key} label={c.label} selected={channel === c.key} onPress={() => setChannel(c.key)} />
          ))}
        </Row>
        {hint ? <T v="caption">{hint}</T> : null}
      </View>

      {okTop ? (
        <Banner tone="success" icon="checked">대가성 문구가 글 맨 앞에 있어요.</Banner>
      ) : (
        <Banner
          tone="warn"
          icon="warn"
          title="대가성 문구가 맨 앞에 없어요"
          action={<Button small kind="ghost" label="맨 앞에 넣기" onPress={() => setText(ensureDisclosureOnTop(text, disclosure))} />}>
          공정위 지침상 문구는 글의 제목이나 첫 부분에 있어야 해요. 지우거나 아래로 옮기지 마세요.
        </Banner>
      )}

      <Field value={text} onChangeText={setText} multiline style={{ minHeight: 260, fontSize: 14, lineHeight: 21 }} />

      <Row>
        <Button
          style={{ flex: 1 }}
          icon="copy"
          label="글 복사"
          disabled={!okTop}
          onPress={() => {
            copy(text, '글을 복사했어요. 올리러 가세요!');
            markPosted();
          }}
        />
        <Button
          style={{ flex: 1 }}
          kind="secondary"
          icon="share"
          label="바로 공유"
          disabled={!okTop}
          onPress={() => {
            share(text);
            markPosted();
          }}
        />
      </Row>
      {channel === 'instagram' && link ? (
        <Button kind="ghost" icon="copy" label="링크만 따로 복사 (프로필·스토리용)" onPress={() => copy(link.url, '링크를 복사했어요')} />
      ) : null}
      {channel === 'instagram' && profileSite ? (
        <Button
          kind="ghost"
          icon="copy"
          label={`${profileSite.label} 주소 복사 (인스타 프로필 링크에 넣기)`}
          onPress={() => copy(profileSite.url, `${profileSite.label} 주소를 복사했어요`)}
        />
      ) : null}
      <T v="caption">가격은 바뀔 수 있어서 글에 &lsquo;게시 시점 기준&rsquo;을 붙여 뒀어요. 후기·장점은 직접 써 보고 확인한 내용만 넣어 주세요.</T>
    </Screen>
  );
}
