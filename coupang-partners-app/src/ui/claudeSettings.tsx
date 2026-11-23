// 설정 → Claude 연결 (PC 설치판에서만). 로그인·파트너스 로그인은 사람이 직접 한다.
import { useCallback, useEffect, useState } from 'react';

import { claudeAgent, type ClaudeStatus } from '../api/claudeAgent';
import { IS_DESKTOP } from '../lib/hosting';
import { Badge, Banner, Button, Card, Icon, Row, Section, T } from './kit';
import { useTheme } from './theme';

export function ClaudeSection() {
  const t = useTheme();
  const [st, setSt] = useState<ClaudeStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  const refresh = useCallback(async () => {
    setBusy(true);
    try {
      setSt(await claudeAgent.status());
    } catch (e) {
      setSt(null);
      setMsg({ ok: false, text: e instanceof Error ? e.message : String(e) });
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => {
    if (!IS_DESKTOP) return;
    let live = true;
    claudeAgent
      .status()
      .then((s) => live && setSt(s))
      .catch(() => live && setSt(null));
    return () => {
      live = false;
    };
  }, []);

  if (!IS_DESKTOP) return null;

  const login = async () => {
    setMsg(null);
    try {
      await claudeAgent.login();
      setMsg({ ok: true, text: '로그인 창을 열었어요. 브라우저에서 Claude 계정으로 허용한 뒤, 아래 "상태 새로 고침"을 눌러 주세요.' });
    } catch (e) {
      setMsg({ ok: false, text: e instanceof Error ? e.message : String(e) });
    }
  };

  const partners = async () => {
    setMsg(null);
    try {
      await claudeAgent.openBrowser('partners');
      setMsg({ ok: true, text: '작업 브라우저 창에 쿠팡 파트너스를 열었어요. 그 창에서 직접 로그인해 두면 Claude가 링크를 만들 수 있어요 (로그인은 그 창에 남아요).' });
    } catch (e) {
      setMsg({ ok: false, text: e instanceof Error ? e.message : String(e) });
    }
  };

  return (
    <Section title="Claude 연결 (AI 상품 찾기)">
      <Card>
        <Row style={{ justifyContent: 'space-between' }}>
          <Row>
            <Icon name="sparkle" color={st?.loggedIn ? t.primary : t.faint} />
            <T v="h3">{!st ? '확인 중…' : !st.installed ? 'Claude Code가 설치돼 있지 않아요' : st.loggedIn ? 'Claude에 로그인돼 있어요' : 'Claude 로그인이 필요해요'}</T>
          </Row>
          {st?.loggedIn && st.plan ? <Badge label={st.plan.toUpperCase()} tone="accent" /> : null}
        </Row>
        {st?.loggedIn && st.email ? <T v="caption">{`계정: ${st.email}`}</T> : null}
        <T v="sub">AI 상품 찾기의 &lsquo;Claude로 검색해서 저장&rsquo;은 이 PC의 Claude Code로 돌아가요. 내 Claude 구독 사용량을 쓰고, 따로 API 키는 넣지 않아요.</T>
        {st && !st.installed ? (
          <Banner tone="info" icon="info" title="설치 방법">
            {'PowerShell에서 irm https://claude.ai/install.ps1 | iex 를 실행한 뒤, 파트너스 매니저를 다시 열어 주세요.'}
          </Banner>
        ) : null}
        <Row>
          {st?.installed ? <Button style={{ flex: 1 }} icon="key" label={st.loggedIn ? '다른 계정으로 로그인' : 'Claude 로그인'} kind={st.loggedIn ? 'secondary' : 'primary'} onPress={login} /> : null}
          <Button style={{ flex: 1 }} kind="ghost" icon="refresh" label="상태 새로 고침" onPress={refresh} loading={busy} />
        </Row>
        <T v="h3">작업 브라우저 (Claude가 다루는 창)</T>
        <T v="sub">Claude는 앱과 따로 떨어진 전용 Edge 창에서 쿠팡을 검색하고 파트너스 링크를 만들어요. 처음 한 번 그 창에서 쿠팡 파트너스에 직접 로그인해 두세요.</T>
        <Button kind="secondary" icon="open" label="파트너스 로그인 창 열기" onPress={partners} />
        <T v="caption">작업 브라우저는 이 PC 안에서만 열리는 디버깅 연결(127.0.0.1:9335)로 다뤄요. 이 PC의 다른 프로그램도 그 창을 조작할 수 있으니, 그 창에서는 쿠팡 파트너스 말고 다른 사이트에 로그인하지 마세요.</T>
        {msg ? (
          <Banner tone={msg.ok ? 'success' : 'danger'} icon={msg.ok ? 'checked' : 'warn'}>
            {msg.text}
          </Banner>
        ) : null}
      </Card>
    </Section>
  );
}
