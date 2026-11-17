// 설정: 네이버 API 키(API HUB 쇼핑인사이트 / 개발자센터 쇼핑 검색)와 홍보 글·영상 옵션.
// 키 값은 저장한 뒤 화면에 다시 보여 주지 않는다 (있음/없음만).
import { useState } from 'react';
import { Pressable } from 'react-native';

import { TREND_CATEGORIES, trendWindow } from '../lib/naverTrend';
import { HOSTING, IS_DESKTOP } from '../lib/hosting';
import { errorText, useApp } from '../store/AppContext';
import { Banner, Button, Card, Chip, Field, Icon, Row, Section, T } from './kit';
import { useTheme } from './theme';

export const APIHUB_CONSOLE = 'https://www.ncloud.com/product/applicationService/apiHub';
export const NAVER_DEV_CENTER = 'https://developers.naver.com/apps/#/register';

function KeyForm({
  kind,
  title,
  has,
  help,
  idLabel,
  secretLabel,
  onTest,
}: {
  kind: 'hub' | 'shop';
  title: string;
  has: boolean;
  help: string;
  idLabel: string;
  secretLabel: string;
  onTest: () => Promise<string>;
}) {
  const { setNaverKeys, clearNaverKeys, ask } = useApp();
  const t = useTheme();
  const [id, setId] = useState('');
  const [secret, setSecret] = useState('');
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [check, setCheck] = useState<{ ok: boolean; text: string } | null>(null);

  const save = async () => {
    if (id.trim().length < 4 || secret.trim().length < 4) {
      setCheck({ ok: false, text: '두 값을 모두 끝까지 붙여 넣어 주세요.' });
      return;
    }
    setBusy(true);
    try {
      await setNaverKeys(kind, id, secret);
      setId('');
      setSecret('');
      setCheck({ ok: true, text: '저장했어요. "연결 확인"으로 키가 맞는지 시험해 보세요 (호출 1회).' });
    } finally {
      setBusy(false);
    }
  };

  const test = async () => {
    setBusy(true);
    setCheck(null);
    try {
      setCheck({ ok: true, text: await onTest() });
    } catch (e) {
      setCheck({ ok: false, text: errorText(e) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card>
      <Row>
        <Icon name="key" color={has ? t.primary : t.faint} />
        <T v="h3" style={{ flex: 1 }}>
          {title}
        </T>
        <T v="caption" color={has ? 'primary' : 'faint'}>
          {has ? '저장됨' : '없음'}
        </T>
      </Row>
      <T v="sub">{help}</T>
      <Field label={idLabel} value={id} onChangeText={setId} placeholder={has ? '새 키로 바꿀 때만 입력' : '붙여 넣기'} autoCapitalize="none" autoCorrect={false} />
      <Field
        label={secretLabel}
        value={secret}
        onChangeText={setSecret}
        placeholder={has ? '새 키로 바꿀 때만 입력' : '붙여 넣기'}
        secureTextEntry={!show}
        autoCapitalize="none"
        autoCorrect={false}
        right={
          <Pressable onPress={() => setShow((v) => !v)} accessibilityLabel={show ? '키 가리기' : '키 보기'} hitSlop={8}>
            <Icon name={show ? 'eyeOff' : 'eye'} color={t.sub} />
          </Pressable>
        }
      />
      <Button label="키 저장" icon="key" onPress={save} loading={busy} disabled={!id || !secret} />
      {has ? (
        <Row>
          <Button style={{ flex: 1 }} kind="secondary" label="연결 확인" icon="bolt" onPress={test} loading={busy} />
          <Button
            style={{ flex: 1 }}
            kind="danger"
            label="키 삭제"
            icon="trash"
            onPress={() => ask({ title: `${title} 키 삭제`, message: '저장된 키를 이 기기에서 지울까요?', ok: '삭제', onOk: () => clearNaverKeys(kind).then(() => setCheck(null)) })}
          />
        </Row>
      ) : null}
      {check ? (
        <Banner tone={check.ok ? 'success' : 'danger'} icon={check.ok ? 'checked' : 'warn'}>
          {check.text}
        </Banner>
      ) : null}
    </Card>
  );
}

export function NaverKeysSection() {
  const { naver, naverHub, naverShop } = useApp();
  const t = useTheme();
  if (HOSTING === 'artifact') {
    return (
      <Section title="네이버 API (쇼핑인사이트·쇼핑 검색)">
        <Card>
          <Row>
            <Icon name="key" color={t.faint} />
            <T v="h3">이 화면에서는 네이버 API를 쓸 수 없어요</T>
          </Row>
          <T v="sub">
            Claude 화면은 보안상 바깥 서버로 요청을 보낼 수 없어서, 네이버 API HUB 키를 넣어도 연결되지 않아요. 키는 여기에 넣지 마세요. 네이버 추천의 &lsquo;뜨는 분야&rsquo;는 휴대폰 앱이나 직접 배포한 웹사이트(Vercel)에서 쓸 수 있어요.
          </T>
          <T v="caption">여기서는 이달에 많이 찾는 말과 내 보관함 상품으로 추천해 드려요.</T>
        </Card>
      </Section>
    );
  }
  const where = IS_DESKTOP
    ? 'PC 설치판에서는 키가 이 앱 전용 브라우저 저장소에만 저장되고, 요청은 이 PC 안의 중계 서버(127.0.0.1)를 거쳐 네이버로 가요 (중계는 아무것도 저장하지 않아요).'
    : HOSTING === 'web'
      ? '웹사이트에서는 키가 이 브라우저에 저장되고, 요청은 이 웹사이트의 중계(/api/naver)를 거쳐 네이버로 가요 (중계는 아무것도 저장하지 않아요). 공용 PC에서는 넣지 마세요.'
      : '키는 이 휴대폰의 보안 저장소에만 저장돼요.';
  return (
    <Section title="네이버 API (쇼핑인사이트·쇼핑 검색)">
      <KeyForm
        kind="hub"
        title="네이버 API HUB"
        has={naverHub}
        idLabel="Client ID (X-NCP-APIGW-API-KEY-ID)"
        secretLabel="Client Secret (X-NCP-APIGW-API-KEY)"
        help={`네이버 추천의 '뜨는 분야'(데이터랩 쇼핑인사이트 — 분야별 클릭 추이)에 써요. 네이버 클라우드 API HUB 콘솔에서 Application을 만들고 쇼핑인사이트를 켠 뒤 받은 값을 넣어요. ${where}`}
        onTest={async () => {
          if (!naver) throw new Error('키를 먼저 저장해 주세요.');
          const w = trendWindow(new Date());
          const c = TREND_CATEGORIES[0];
          const rows = await naver.categoryTrend([{ name: c.name, param: c.naver }], w.startDate, w.endDate);
          return `연결됐어요. 쇼핑인사이트 ${c.name} 분야 ${rows[0]?.data.length ?? 0}일치를 받아 왔어요.`;
        }}
      />
      <Button kind="ghost" icon="open" label="네이버 API HUB 열기" href={APIHUB_CONSOLE} />
      <KeyForm
        kind="shop"
        title="네이버 쇼핑 검색 (선택)"
        has={naverShop}
        idLabel="Client ID (X-Naver-Client-Id)"
        secretLabel="Client Secret (X-Naver-Client-Secret)"
        help="쿠팡 파트너스 API 키가 아직 없을 때 네이버 쇼핑 검색으로 상품 후보를 찾아요. API HUB에는 쇼핑 검색이 없어서 네이버 개발자센터에서 '검색' API Application을 따로 만들어야 해요. 네이버 상품 링크는 수수료 링크가 아니에요."
        onTest={async () => {
          if (!naver) throw new Error('키를 먼저 저장해 주세요.');
          const items = await naver.shopSearch('가습기', 1);
          return `연결됐어요. 네이버 쇼핑 검색 결과 ${items.length}개를 받아 왔어요.`;
        }}
      />
      <Button kind="ghost" icon="open" label="네이버 개발자센터 열기" href={NAVER_DEV_CENTER} />
      <T v="caption">네이버 호출은 시간당 60회까지만 보내고, 같은 추천은 6시간 동안 저장된 결과를 다시 써요.</T>
    </Section>
  );
}

export function PostVideoSection() {
  const { settings, updateSettings, showToast } = useApp();
  const [pen, setPen] = useState(settings.penName);
  return (
    <Section title="홍보 글·영상 (자비스 방식)">
      <Card>
        <T v="sub">블로그 소개 글의 글쓴이 이름과, 영상 끝 장면·인스타 캡션의 구매 안내 방식이에요.</T>
        <Field
          label="글쓴이 이름"
          value={pen}
          onChangeText={setPen}
          placeholder="예: 찰스"
          help="글 끝에 '○○가 직접 써 보거나 다녀온 후기는 아니에요'로 들어가요."
          right={
            <Button
              label="저장"
              kind="secondary"
              onPress={async () => {
                const v = pen.trim().slice(0, 20) || '찰스';
                setPen(v);
                await updateSettings({ penName: v });
                showToast('글쓴이 이름을 저장했어요');
              }}
            />
          }
        />
        <T v="h3">영상 끝 안내</T>
        <Row gap={8} style={{ flexWrap: 'wrap' }}>
          <Chip label="댓글 '가격' → DM" selected={settings.reelCta === 'dm'} onPress={() => updateSettings({ reelCta: 'dm' })} />
          <Chip label="프로필 링크" selected={settings.reelCta === 'profile'} onPress={() => updateSettings({ reelCta: 'profile' })} />
        </Row>
        <T v="caption">
          {settings.reelCta === 'profile'
            ? `인스타 프로필 링크를 ${settings.sites[0]?.label ?? '내 채널'}로 바꿔 둔 경우에 골라요.`
            : '댓글로 "가격"을 남긴 사람에게 링크를 DM으로 보내는 방식이에요 (DM은 직접 보내요).'}
        </T>
      </Card>
    </Section>
  );
}
