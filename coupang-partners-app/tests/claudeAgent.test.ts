/// <reference types="node" />
// PC판 서버의 'Claude로 상품 찾기'를 가짜 claude 실행 파일 + 가짜 작업 브라우저 포트로 끝까지 돌려 본다 (진짜 Claude 사용량 0).
import assert from 'node:assert/strict';
import { spawn, type ChildProcess } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, test } from 'node:test';

// eslint-disable-next-line @typescript-eslint/no-require-imports
const agent = require('../desktop/claudeAgent.cjs') as { buildPrompt: (q: string, c: string) => string; stepText: (n: string, i: unknown) => string };

const PORT = 8794;
const BASE = `http://127.0.0.1:${PORT}`;
const dir = mkdtempSync(join(tmpdir(), 'pm-agent-'));
let cdp: Server;
let server: ChildProcess;

const FAKE = `
const fs = require('fs');
const prompt = fs.readFileSync(0, 'utf8');
fs.writeFileSync(${JSON.stringify(join(dir, 'seen.json'))}, JSON.stringify({ args: process.argv.slice(2), prompt, apiKey: process.env.ANTHROPIC_API_KEY || null }));
if (process.argv.includes('auth')) { console.log(JSON.stringify({ loggedIn: true, authMethod: 'claude.ai', email: 'charles@example.com', subscriptionType: 'pro' })); process.exit(0); }
const out = (o) => process.stdout.write(JSON.stringify(o) + '\\n');
out({ type: 'system', subtype: 'init', mcp_servers: [{ name: 'browser', status: 'connected' }] });
out({ type: 'assistant', message: { content: [{ type: 'tool_use', name: 'mcp__browser__browser_navigate', input: { url: 'https://www.coupang.com/np/search?q=%EB%AC%BC%ED%8B%B0%EC%8A%88' } }] } });
setTimeout(() => {
  out({ type: 'result', is_error: false, num_turns: 9, total_cost_usd: 0.42, result: '다 했어요\\n파트너스매니저 {"name":"캡형 물티슈 100매","price":12900,"link":"https://link.coupang.com/a/fake1","product_url":"https://www.coupang.com/vp/products/1","rocket":true,"note":"낮은가격순"}' });
}, 300);
`;

before(async () => {
  writeFileSync(join(dir, 'fake-claude.cjs'), FAKE);
  let exe = join(dir, 'fake-claude.cmd');
  if (process.platform === 'win32') writeFileSync(exe, `@node "%~dp0fake-claude.cjs" %*\r\n`);
  else {
    exe = join(dir, 'fake-claude');
    writeFileSync(exe, `#!/bin/sh\nexec node "${join(dir, 'fake-claude.cjs')}" "$@"\n`, { mode: 0o755 });
  }
  cdp = createServer((req, res) => res.end(req.url?.startsWith('/json/version') ? '{"Browser":"fake"}' : '{}'));
  await new Promise<void>((r) => cdp.listen(0, '127.0.0.1', () => r()));
  server = spawn(process.execPath, [join(__dirname, '..', 'desktop', 'server.cjs')], {
    env: {
      ...process.env,
      PM_PORT: String(PORT),
      PM_CLAUDE_PATH: exe,
      PM_AGENT_PORT: String((cdp.address() as AddressInfo).port),
      ANTHROPIC_API_KEY: 'sk-should-not-pass',
      LOCALAPPDATA: dir,
    },
    stdio: 'ignore',
  });
  for (let i = 0; i < 40; i++) {
    try {
      if ((await fetch(`${BASE}/__health`)).ok) return;
    } catch {
      /* 아직 */
    }
    await new Promise((r) => setTimeout(r, 150));
  }
  throw new Error('서버가 뜨지 않았어요');
});

after(() => {
  server?.kill();
  cdp?.close();
});

const H = { 'X-PM': '1', 'Content-Type': 'application/json' };

test('앱 헤더(X-PM) 없는 요청·다른 출처는 막는다', async () => {
  assert.equal((await fetch(`${BASE}/api/claude/status`)).status, 403);
  assert.equal((await fetch(`${BASE}/api/claude/search`, { method: 'POST', headers: { ...H, Origin: 'https://evil.example' }, body: '{"q":"물티슈"}' })).status, 403);
});

test('로그인 상태: 이메일은 일부만', async () => {
  const s = await (await fetch(`${BASE}/api/claude/status`, { headers: H })).json();
  assert.deepEqual(s, { installed: true, loggedIn: true, method: 'claude.ai', email: 'cha***@example.com', plan: 'pro' });
});

test('검색: 진행 단계·결과·사용량, 안전한 실행 옵션, API 키는 안 넘김', async () => {
  const bad = await fetch(`${BASE}/api/claude/search`, { method: 'POST', headers: H, body: '{"q":"x"}' });
  assert.equal(bad.status, 400);
  const j0 = await (await fetch(`${BASE}/api/claude/search`, { method: 'POST', headers: H, body: JSON.stringify({ q: '물티슈\n100매', cond: '로켓배송' }) })).json();
  assert.equal(j0.state, 'running');
  assert.equal(j0.q, '물티슈 100매');
  const busy = await fetch(`${BASE}/api/claude/search`, { method: 'POST', headers: H, body: '{"q":"가습기"}' });
  assert.equal(busy.status, 409);
  let j = j0;
  for (let i = 0; i < 60 && j.state === 'running'; i++) {
    await new Promise((r) => setTimeout(r, 200));
    j = await (await fetch(`${BASE}/api/claude/job?id=${j0.id}`, { headers: H })).json();
  }
  assert.equal(j.state, 'done');
  assert.equal(j.cost, 0.42);
  assert.equal(j.turns, 9);
  assert.match(j.result, /link\.coupang\.com\/a\/fake1/);
  assert.ok(j.steps.some((s: { text: string }) => s.text === '작업 브라우저에 연결됐어요'));
  assert.ok(j.steps.some((s: { text: string }) => s.text === "여는 중: 쿠팡 검색 '물티슈'"));

  const seen = JSON.parse(readFileSync(join(dir, 'seen.json'), 'utf8'));
  assert.equal(seen.apiKey, null, 'API 키를 자식 프로세스에 넘기지 않는다');
  const a: string[] = seen.args;
  assert.equal(a[a.indexOf('--permission-mode') + 1], 'dontAsk');
  assert.ok(a.includes('--strict-mcp-config'));
  assert.ok(a.includes('--no-session-persistence'));
  const allowed = a[a.indexOf('--allowedTools') + 1].split(',');
  assert.ok(!allowed.some((x) => /Bash|PowerShell|Write|Edit|evaluate|run_code|file_upload/.test(x)));
  assert.match(a[a.indexOf('--disallowedTools') + 1], /mcp__browser__browser_evaluate/);
  assert.match(seen.prompt, /찾을 상품: 물티슈 100매\n조건: 로켓배송/);
  assert.match(seen.prompt, /비밀번호·인증번호는 절대 입력하지 않는다/);
  assert.match(seen.prompt, /장바구니·바로구매·결제/);
});

test('부탁 글: 한 줄로 접고 길이를 자른다는 전제의 내용', () => {
  const p = agent.buildPrompt('가습기', '');
  assert.match(p, /sorter=salePriceAsc/);
  assert.match(p, /"error":"partners_login"/);
  assert.ok(!/조건:/.test(p));
  assert.equal(agent.stepText('mcp__browser__browser_click', { element: '링크 생성 버튼' }), '누르는 중: 링크 생성 버튼');
  assert.equal(agent.stepText('mcp__browser__browser_navigate', { url: 'https://partners.coupang.com/#x' }), '여는 중: 쿠팡 파트너스');
});
