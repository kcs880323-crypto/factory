// PC판: Claude가 작업 브라우저로 쿠팡 검색·파트너스 링크 만들기를 하는 동안의 진행 표시.
import { useCallback, useEffect, useRef, useState } from 'react';
import { View } from 'react-native';

import { claudeAgent, ClaudeAgentError, resultError, type ClaudeJob } from '../api/claudeAgent';
import { Banner, Button, Card, Icon, Loading, Row, T } from './kit';
import { useTheme } from './theme';

/** 작업 하나를 시작하고 1.5초마다 상태를 읽는다. 끝나면 onFinished(결과 글)를 한 번 부른다. */
export function useClaudeJob(onFinished: (job: ClaudeJob) => void) {
  const [job, setJob] = useState<ClaudeJob | null>(null);
  const [error, setError] = useState<{ code: string; text: string } | null>(null);
  const [starting, setStarting] = useState(false);
  const alive = useRef(true);
  const cb = useRef(onFinished);
  useEffect(() => {
    cb.current = onFinished;
  }, [onFinished]);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  const start = useCallback(async (q: string, cond: string) => {
    setError(null);
    setStarting(true);
    let j: ClaudeJob;
    try {
      j = await claudeAgent.search(q, cond);
      setJob(j);
    } catch (e) {
      setError({ code: e instanceof ClaudeAgentError ? e.code : 'error', text: e instanceof Error ? e.message : String(e) });
      return;
    } finally {
      setStarting(false);
    }
    // 끝날 때까지 1.5초마다 읽는다 (화면을 떠나면 그만 읽는다 — 작업은 서버에서 계속된다)
    while (alive.current && j.state === 'running') {
      await new Promise((r) => setTimeout(r, 1500));
      try {
        j = await claudeAgent.job(j.id);
        if (alive.current) setJob(j);
      } catch (e) {
        setError({ code: e instanceof ClaudeAgentError ? e.code : 'error', text: e instanceof Error ? e.message : String(e) });
        return;
      }
    }
    if (alive.current && j.state !== 'running') cb.current(j);
  }, []);

  const stop = useCallback(async () => {
    try {
      await claudeAgent.stop();
    } catch {
      /* 다음 읽기에서 상태가 보인다 */
    }
  }, []);

  return { job, error, starting, running: starting || job?.state === 'running', start, stop };
}

function secs(job: ClaudeJob, now: number) {
  return Math.max(0, Math.round(((job.endedAt ?? now) - job.startedAt) / 1000));
}

export function ClaudeJobView({ job, onOpenPartners }: { job: ClaudeJob; onOpenPartners: () => void }) {
  const t = useTheme();
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (job.state !== 'running') return;
    const i = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(i);
  }, [job.state]);
  const kind = job.state === 'done' ? resultError(job.result) : null;
  const steps = job.steps.slice(-8);
  return (
    <Card style={{ gap: 8 }}>
      <Row style={{ justifyContent: 'space-between' }}>
        <T v="h3">{`Claude가 '${job.q}' 찾는 중`}</T>
        <T v="caption">{`${secs(job, now)}초`}</T>
      </Row>
      <View style={{ gap: 4 }}>
        {steps.map((s, i) => (
          <Row key={`${s.t}-${i}`} gap={6}>
            <Icon name={i === steps.length - 1 && job.state === 'running' ? 'refresh' : 'checked'} size={16} color={i === steps.length - 1 && job.state === 'running' ? t.primary : t.faint} />
            <T v="caption" style={{ flex: 1 }}>
              {s.text}
            </T>
          </Row>
        ))}
      </View>
      {job.state === 'running' ? <Loading label="작업 브라우저 창에서 Claude가 일하는 모습을 볼 수 있어요" /> : null}
      {job.state === 'error' ? (
        <Banner tone="danger" icon="warn" title="Claude가 끝내지 못했어요">
          {job.error}
        </Banner>
      ) : null}
      {job.state === 'stopped' ? <Banner tone="info" icon="info">멈췄어요. 작업 브라우저에서 이미 한 일(열린 페이지)은 그대로 남아 있어요.</Banner> : null}
      {kind === 'partners_login' ? (
        <Banner tone="warn" icon="key" title="쿠팡 파트너스 로그인이 필요해요" action={<Button small kind="ghost" label="파트너스 로그인 창 열기" onPress={onOpenPartners} />}>
          작업 브라우저 창에서 쿠팡 파트너스에 직접 로그인한 뒤 다시 찾아 주세요. 로그인은 그 창에 남아 다음부터는 바로 돼요.
        </Banner>
      ) : null}
      {kind === 'not_found' ? <Banner tone="info" icon="search">같은 상품을 찾지 못했어요. 검색어를 더 구체적으로 바꿔 보세요.</Banner> : null}
      {job.state !== 'running' && job.cost !== null ? (
        <T v="caption">{`Claude 사용량 약 $${job.cost.toFixed(2)} 상당 · ${job.turns ?? '-'}단계 (구독이면 따로 청구되지 않고 사용 한도에서 빠져요)`}</T>
      ) : null}
    </Card>
  );
}
