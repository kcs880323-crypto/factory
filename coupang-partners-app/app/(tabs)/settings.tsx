import { router } from 'expo-router';
import { useState } from 'react';
import { Pressable, Switch, View } from 'react-native';

import { DEFAULT_DISCLOSURE } from '@/src/lib/compose';
import { isValidSubId } from '@/src/lib/links';
import { clampLimit, DEFAULT_HOURLY_LIMIT } from '@/src/lib/rateGuard';
import { errorText, useApp } from '@/src/store/AppContext';
import { Badge, Banner, Button, Card, Chip, Field, Icon, Row, Screen, Section, T } from '@/src/ui/kit';
import { confirm, openUrl, PARTNERS_SITE, UsageMeter } from '@/src/ui/parts';
import { useTheme } from '@/src/ui/theme';

export default function Settings() {
  const app = useApp();
  const { settings, updateSettings, hasKeys, setApiKeys, clearApiKeys, client, links, share, showToast } = app;
  const t = useTheme();
  const [accessKey, setAccessKey] = useState('');
  const [secretKey, setSecretKey] = useState('');
  const [showSecret, setShowSecret] = useState(false);
  const [keyBusy, setKeyBusy] = useState(false);
  const [check, setCheck] = useState<{ ok: boolean; text: string } | null>(null);
  const [subId, setSubId] = useState(settings.subId);
  const [newChannel, setNewChannel] = useState('');
  const [disclosure, setDisclosure] = useState(settings.disclosure);
  const [limit, setLimit] = useState(String(settings.hourlyLimit));

  const saveKeys = async () => {
    if (accessKey.trim().length < 10 || secretKey.trim().length < 10) {
      setCheck({ ok: false, text: '두 키를 모두 끝까지 붙여 넣어 주세요.' });
      return;
    }
    setKeyBusy(true);
    try {
      await setApiKeys({ accessKey, secretKey });
      setAccessKey('');
      setSecretKey('');
      setCheck({ ok: true, text: '저장했어요. 아래 "연결 확인"으로 키가 맞는지 시험해 보세요 (API 1회).' });
      if (settings.demoMode) await updateSettings({ demoMode: false });
    } finally {
      setKeyBusy(false);
    }
  };

  const testKeys = async () => {
    if (!client || settings.demoMode) return;
    setKeyBusy(true);
    setCheck(null);
    try {
      const items = await client.goldbox({ subId: settings.subId || undefined });
      setCheck({ ok: true, text: `연결됐어요. 오늘의 골드박스 상품 ${items.length}개를 받아 왔어요.` });
    } catch (e) {
      setCheck({ ok: false, text: errorText(e) });
    } finally {
      setKeyBusy(false);
    }
  };

  const saveSubId = async () => {
    const v = subId.trim();
    if (!isValidSubId(v)) return showToast('채널 ID는 영문·숫자만 써 주세요');
    await updateSettings({ subId: v, channels: v && !settings.channels.includes(v) ? [...settings.channels, v] : settings.channels });
    showToast('기본 채널을 저장했어요');
  };

  const addChannel = async () => {
    const v = newChannel.trim();
    if (!v) return;
    if (!isValidSubId(v)) return showToast('채널 ID는 영문·숫자만 써 주세요');
    if (!settings.channels.includes(v)) await updateSettings({ channels: [...settings.channels, v] });
    setNewChannel('');
  };

  const exportLinks = () => {
    if (!links.length) return showToast('보관함이 비어 있어요');
    const csv = [
      '이름,링크,가격,채널,메모,저장일',
      ...links.map((l) =>
        [l.title, l.url, l.price ?? '', l.subId ?? '', l.memo ?? '', new Date(l.createdAt).toISOString().slice(0, 10)]
          .map((v) => `"${String(v).replace(/"/g, '""')}"`)
          .join(','),
      ),
    ].join('\n');
    share(csv);
  };

  return (
    <Screen>
      <Section title="쿠팡 파트너스 API">
        <Card>
          <Row style={{ justifyContent: 'space-between' }}>
            <Row>
              <Icon name="key" color={hasKeys ? t.primary : t.faint} />
              <T v="h3">{hasKeys ? 'API 키가 저장돼 있어요' : 'API 키 없음 (수동 모드)'}</T>
            </Row>
            {settings.demoMode ? <Badge label="예시 모드 켜짐" tone="demo" /> : null}
          </Row>
          <T v="sub">
            키는 파트너스 사이트의 파트너스 API 메뉴에서 받아요 (사이트 개편에 따라 메뉴 이름은 다를 수 있어요). 키는 이 휴대폰의 보안 저장소에만 저장되고 다른 곳으로 보내지 않아요.
          </T>
          <Field label="Access Key" value={accessKey} onChangeText={setAccessKey} placeholder={hasKeys ? '새 키로 바꿀 때만 입력' : '붙여 넣기'} autoCapitalize="none" autoCorrect={false} />
          <Field
            label="Secret Key"
            value={secretKey}
            onChangeText={setSecretKey}
            placeholder={hasKeys ? '새 키로 바꿀 때만 입력' : '붙여 넣기'}
            secureTextEntry={!showSecret}
            autoCapitalize="none"
            autoCorrect={false}
            right={
              <Pressable onPress={() => setShowSecret((v) => !v)} accessibilityLabel={showSecret ? '키 가리기' : '키 보기'} hitSlop={8}>
                <Icon name={showSecret ? 'eyeOff' : 'eye'} color={t.sub} />
              </Pressable>
            }
          />
          <Button label="키 저장" icon="key" onPress={saveKeys} loading={keyBusy} disabled={!accessKey || !secretKey} />
          {hasKeys ? (
            <Row>
              <Button style={{ flex: 1 }} kind="secondary" label="연결 확인" icon="bolt" onPress={testKeys} loading={keyBusy} disabled={settings.demoMode} />
              <Button
                style={{ flex: 1 }}
                kind="danger"
                label="키 삭제"
                icon="trash"
                onPress={() => confirm('API 키 삭제', '저장된 키를 이 휴대폰에서 지울까요?', '삭제', () => clearApiKeys().then(() => setCheck(null)))}
              />
            </Row>
          ) : null}
          {settings.demoMode && hasKeys ? <T v="caption">예시 모드를 끄면 실제 API로 연결돼요.</T> : null}
          {check ? <Banner tone={check.ok ? 'success' : 'danger'} icon={check.ok ? 'checked' : 'warn'}>{check.text}</Banner> : null}
        </Card>
      </Section>

      <Section title="호출 한도 (계정 보호)">
        <Card>
          <UsageMeter />
          <T v="sub">
            쿠팡은 짧은 시간에 호출이 몰리면 계정을 일시 정지하고, 반복되면 영구 정지할 수 있다고 알려져 있어요. 앱은 최근 1시간 호출 수가 아래 한도에 닿으면 더 부르지 않아요. 같은 검색·리포트는 1시간 동안 저장된 결과를 써서 호출을 아껴요.
          </T>
          <Field
            label="시간당 최대 호출 수"
            value={limit}
            onChangeText={setLimit}
            keyboardType="number-pad"
            help={`기본 ${DEFAULT_HOURLY_LIMIT}회 (5~200). 쿠팡 공지에서 정확한 한도를 확인한 뒤에만 올리세요.`}
            onEndEditing={async () => {
              const v = clampLimit(Number(limit));
              setLimit(String(v));
              await updateSettings({ hourlyLimit: v });
            }}
          />
        </Card>
      </Section>

      <Section title="채널 ID (subId)">
        <Card>
          <T v="sub">파트너스 사이트에 등록한 채널 ID를 넣으면, API로 만든 링크에 채널이 붙어서 어느 블로그·SNS에서 수익이 났는지 나눠 볼 수 있어요.</T>
          <Field
            label="기본 채널 ID"
            value={subId}
            onChangeText={setSubId}
            autoCapitalize="none"
            autoCorrect={false}
            placeholder="예: blog01"
            error={isValidSubId(subId.trim()) ? null : '영문·숫자만 쓸 수 있어요'}
            right={<Button label="저장" kind="secondary" onPress={saveSubId} />}
          />
          <T v="h3">채널 목록</T>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
            {settings.channels.length === 0 ? <T v="caption">아직 없어요.</T> : null}
            {settings.channels.map((c) => (
              <Chip
                key={c}
                label={`${c}  ✕`}
                onPress={() =>
                  confirm('채널 지우기', `채널 목록에서 ${c}를 지울까요? (저장된 링크는 그대로예요)`, '지우기', () =>
                    updateSettings({ channels: settings.channels.filter((x) => x !== c), subId: settings.subId === c ? '' : settings.subId }),
                  )
                }
              />
            ))}
          </View>
          <Field
            value={newChannel}
            onChangeText={setNewChannel}
            placeholder="채널 ID 추가 (예: insta01)"
            autoCapitalize="none"
            autoCorrect={false}
            onSubmitEditing={addChannel}
            right={<Button label="추가" kind="secondary" onPress={addChannel} />}
          />
        </Card>
      </Section>

      <Section title="대가성 문구">
        <Card>
          <T v="sub">홍보 글 맨 앞에 자동으로 들어가는 문구예요. 공정위 지침에 따라 글의 제목이나 첫 부분에 보이게 둬야 해요.</T>
          <Field value={disclosure} onChangeText={setDisclosure} multiline style={{ minHeight: 90 }} />
          <Row>
            <Button
              style={{ flex: 1 }}
              label="저장"
              onPress={async () => {
                const v = disclosure.trim() || DEFAULT_DISCLOSURE;
                setDisclosure(v);
                await updateSettings({ disclosure: v });
                showToast('문구를 저장했어요');
              }}
            />
            <Button
              style={{ flex: 1 }}
              kind="ghost"
              label="기본 문구로"
              onPress={async () => {
                setDisclosure(DEFAULT_DISCLOSURE);
                await updateSettings({ disclosure: DEFAULT_DISCLOSURE });
              }}
            />
          </Row>
        </Card>
      </Section>

      <Section title="둘러보기·데이터">
        <Card>
          <Row style={{ justifyContent: 'space-between' }}>
            <View style={{ flex: 1, gap: 2 }}>
              <T v="h3">예시 데이터 모드</T>
              <T v="caption">API 키 없이 상품·실적 화면을 가짜 값으로 둘러봐요.</T>
            </View>
            <Switch
              value={settings.demoMode}
              onValueChange={(v) => updateSettings({ demoMode: v })}
              trackColor={{ true: t.demo, false: t.border }}
            />
          </Row>
          <Button kind="secondary" icon="share" label={`링크 보관함 내보내기 (CSV, ${links.length}개)`} onPress={exportLinks} />
          <Button kind="ghost" icon="refresh" label="저장된 검색·리포트 결과 비우기" onPress={() => app.clearCache().then(() => showToast('비웠어요'))} />
          <Button
            kind="danger"
            icon="trash"
            label="모든 데이터 지우기"
            onPress={() =>
              confirm('모두 지우기', 'API 키, 링크 보관함, 설정, 저장된 결과를 이 휴대폰에서 모두 지울까요? 되돌릴 수 없어요.', '모두 지우기', () =>
                app.resetAll().then(() => {
                  setSubId('');
                  setDisclosure(DEFAULT_DISCLOSURE);
                  setLimit(String(DEFAULT_HOURLY_LIMIT));
                  setCheck(null);
                  showToast('모두 지웠어요');
                }),
              )
            }
          />
        </Card>
      </Section>

      <Section title="도움말">
        <Card>
          <Button kind="secondary" icon="book" label="가입·운영 가이드" onPress={() => router.push('/guide')} />
          <Button kind="ghost" icon="open" label="쿠팡 파트너스 사이트 열기" onPress={() => openUrl(PARTNERS_SITE)} />
          <T v="caption">이 앱은 쿠팡이 만든 공식 앱이 아니라, 파트너스 활동을 정리하는 개인용 도구예요. 약관·정산 기준은 항상 파트너스 사이트가 우선이에요.</T>
        </Card>
      </Section>
    </Screen>
  );
}
