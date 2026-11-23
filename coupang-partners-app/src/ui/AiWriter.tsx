// 자비스 방식 '쓰기 → 코드 검사 → 걸리면 한 번 고치기' 흐름.
// - Claude 화면: 버튼 한 번 (sample — 보는 사람의 Claude 사용량, 처음에 허락을 묻는다).
// - 휴대폰 앱·웹사이트: 프롬프트 복사 → Claude 앱에 붙여 넣기 → 받은 답 붙여 넣기 (검사는 이 앱이 한다).
import { useEffect, useRef, useState } from 'react';
import { View } from 'react-native';

import { getSampler, sampleErrorText, type Sampler } from '../lib/ai';
import { useApp } from '../store/AppContext';
import { Banner, Button, Card, Field, Row, T } from './kit';

export interface AiResult<D> {
  draft: D;
  /** 한 번 고친 뒤에도 남은 오류 */
  errors: string[];
  /** 몇 번 받았나 (1 또는 2) */
  rounds: number;
}

export function AiWriter<D>({
  prompt,
  parse,
  check,
  fixPrompt,
  onDone,
  what,
  disabled,
}: {
  prompt: string;
  parse: (text: string) => D;
  check: (d: D) => string[];
  fixPrompt: (d: D, errors: string[]) => string;
  onDone: (r: AiResult<D>) => void;
  /** '블로그 글' / '영상 대본' */
  what: string;
  disabled?: boolean;
}) {
  const { copy } = useApp();
  const [sampler, setSampler] = useState<Sampler | null>(null);
  const [checked, setChecked] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [paste, setPaste] = useState('');
  // 붙여 넣기 방식: 첫 답이 걸리면 고칠 점 프롬프트를 보여 주고 두 번째 답을 받는다
  const [pending, setPending] = useState<{ draft: D; errors: string[] } | null>(null);
  const ctl = useRef<AbortController | null>(null);

  useEffect(() => {
    let live = true;
    getSampler().then((s) => {
      if (!live) return;
      setSampler(() => s);
      setChecked(true);
    });
    return () => {
      live = false;
      ctl.current?.abort();
    };
  }, []);

  const runClaude = async () => {
    if (!sampler || busy) return;
    setBusy(true);
    setErr(null);
    setPending(null);
    ctl.current = new AbortController();
    try {
      setStatus('Claude가 쓰는 중… (처음엔 생각하느라 10~60초 걸려요)');
      const first = await sampler(prompt, {
        signal: ctl.current.signal,
        modelTier: 'default',
        onText: ({ text }) => setStatus(`Claude가 쓰는 중… ${text.length.toLocaleString('ko-KR')}자`),
      });
      let draft = parse(first.text);
      let errors = check(draft);
      let rounds = 1;
      if (errors.length) {
        setStatus(`검사에 ${errors.length}곳이 걸려서 한 번 고치는 중…`);
        const second = await sampler(fixPrompt(draft, errors), {
          signal: ctl.current.signal,
          modelTier: 'default',
          onText: ({ text }) => setStatus(`고치는 중… ${text.length.toLocaleString('ko-KR')}자`),
        });
        draft = parse(second.text);
        errors = check(draft);
        rounds = 2;
      }
      setStatus('');
      onDone({ draft, errors, rounds });
    } catch (e) {
      setStatus('');
      setErr((e as { code?: string })?.code ? sampleErrorText(e) : e instanceof Error ? e.message : sampleErrorText(e));
    } finally {
      setBusy(false);
    }
  };

  const checkPasted = () => {
    setErr(null);
    try {
      const draft = parse(paste);
      const errors = check(draft);
      if (errors.length && !pending) {
        setPending({ draft, errors });
        return;
      }
      setPending(null);
      setPaste('');
      onDone({ draft, errors, rounds: pending ? 2 : 1 });
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <View style={{ gap: 10 }}>
      {sampler ? (
        <Card>
          <T v="h3">✨ Claude로 {what} 쓰기</T>
          <T v="sub">자비스와 같은 규칙으로 써요: 상품 정보·메모에 있는 사실과 숫자만, 써 본 척 금지. 다 쓰면 앱이 검사하고, 걸리면 한 번 고쳐 달라고 해요 (내 Claude 사용량을 써요).</T>
          <Row>
            <Button style={{ flex: 1 }} icon="sparkle" label={busy ? '쓰는 중…' : `${what} 쓰기`} onPress={runClaude} loading={busy} disabled={disabled || busy} />
            {busy ? <Button kind="ghost" icon="stop" label="멈추기" onPress={() => ctl.current?.abort()} /> : null}
          </Row>
          {status ? <T v="caption">{status}</T> : null}
        </Card>
      ) : null}

      {checked ? (
        <Card>
          <T v="h3">{sampler ? '또는 다른 AI 앱에서 쓰기' : `🤖 Claude 앱으로 ${what} 쓰기`}</T>
          <T v="sub">1. 프롬프트를 복사해 Claude(또는 다른 AI) 앱에 붙여 넣어요.  2. 받은 답 전체를 아래 칸에 붙여 넣고 &lsquo;검사하기&rsquo;를 눌러요.</T>
          <Button kind="secondary" icon="copy" label="1. 프롬프트 복사" disabled={disabled} onPress={() => copy(prompt, '프롬프트를 복사했어요. Claude 앱에 붙여 넣으세요')} />
          {pending ? (
            <Banner tone="warn" icon="warn" title={`첫 답에서 ${pending.errors.length}곳이 걸렸어요`}>
              {`${pending.errors.slice(0, 4).join(' / ')}\n아래 '고칠 점 프롬프트'를 복사해 같은 대화에 붙여 넣고, 새 답을 다시 붙여 넣어 주세요.`}
            </Banner>
          ) : null}
          {pending ? (
            <Row>
              <Button style={{ flex: 1 }} kind="secondary" icon="copy" label="고칠 점 프롬프트 복사" onPress={() => copy(fixPrompt(pending.draft, pending.errors), '고칠 점 프롬프트를 복사했어요')} />
              <Button
                style={{ flex: 1 }}
                kind="ghost"
                label="그대로 진행"
                onPress={() => {
                  onDone({ draft: pending.draft, errors: pending.errors, rounds: 1 });
                  setPending(null);
                  setPaste('');
                }}
              />
            </Row>
          ) : null}
          <Field value={paste} onChangeText={setPaste} multiline style={{ minHeight: 110, fontSize: 13 }} placeholder="2. AI가 준 답(JSON)을 그대로 붙여 넣기" />
          <Button icon="checked" label={pending ? '고친 답 검사하기' : '2. 검사하기'} onPress={checkPasted} disabled={!paste.trim()} />
        </Card>
      ) : null}

      {err ? (
        <Banner tone="danger" icon="warn" title="받지 못했어요">
          {err}
        </Banner>
      ) : null}
    </View>
  );
}
