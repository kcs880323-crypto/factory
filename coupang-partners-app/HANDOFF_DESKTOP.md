# 데스크톱 세션으로 이어 가기 — 다음 할 일

브랜치 `ccr-73dc9db4-uk1phh`, PR #5 (초안, 아직 안 합침). 파트너스 매니저의 PC 설치판(`desktop/`)은 찰스님 PC에서 돌아가는 것이 화면으로 확인됐다 (AI 상품 찾기 화면, 2026-11-17).

## 찰스님 요청 (아직 안 함)

> "AI 상품 찾기에서 찾을 제품을 적고 검색하면, (복사·붙여 넣기 없이) 앱이 Claude를 써서 바로 하게 해 줘. 설정에 Claude 로그인 기능을 넣어도 돼."

지금은 'Claude에게 맡기기'가 부탁 복사 → Claude 앱에 붙여 넣기 → 결과 줄 붙여 넣기다. 이것을 PC판 안에서 버튼 하나로 바꾼다.

## 설계 (PC판 전용 — `desktop/server.cjs`에 붙인다)

1. **Claude 실행**: PC의 Claude Code CLI를 서버가 부른다.
   - 찾는 곳: PATH의 `claude`, `%USERPROFILE%\.local\bin\claude.exe`, `%APPDATA%\npm\claude.cmd`. 설정에서 경로를 직접 정할 수도 있게 한다.
   - `.cmd`이면 `cmd.exe /d /s /c`로 실행한다. **프롬프트는 stdin으로 넘긴다** (명령줄 따옴표·길이 문제를 피하려고).
   - `ANTHROPIC_API_KEY` 같은 API 키 환경변수는 자식 프로세스에 넘기지 않는다 (구독 대신 종량제로 나가지 않게).
2. **브라우저**: Claude가 다룰 **전용 Edge**를 따로 띄운다. 앱 창의 브라우저와 다른 프로필이라 쿠팡 키가 그 안에 없다.
   - 실행: `msedge --remote-debugging-port=9335 --user-data-dir=%LOCALAPPDATA%\PartnersManager\agent-browser --no-first-run`
   - 찰스님이 그 창에서 쿠팡 파트너스에 한 번 직접 로그인한다. 설정에 '파트너스 로그인 창 열기' 버튼을 둔다.
   - Claude는 Playwright MCP로 그 창에 붙는다 (자비스 `music_backup`과 같은 방식). Playwright가 직접 띄운 브라우저는 자동화 표시가 남아 쿠팡이 막을 수 있어서 이 방식을 쓴다.
3. **MCP 설정 파일**: 임시 파일로 만든다. 윈도에서는 `npx`를 `cmd /c`로 감싼다.
   `{"mcpServers":{"browser":{"command":"cmd","args":["/c","npx","-y","@playwright/mcp@0.0.83","--cdp-endpoint","http://127.0.0.1:9335"]}}}`
   - 버전은 고정한다 (자비스가 확인한 0.0.83).
   - 환경: `MCP_TIMEOUT=120000`, `MCP_CONNECTION_NONBLOCKING=0` (자비스 실측 — 첫 실행이 느리고, 비동기 연결이면 도구가 안 보임).
4. **명령**: 아래 플래그는 CLI 2.1.326 `--help`로 이름이 있는 것만 확인했고, 실제로 돌려 보지는 않았다.
   ```
   claude -p --output-format stream-json --verbose --no-session-persistence
          --model sonnet --strict-mcp-config --mcp-config <파일>
          --allowedTools mcp__browser__browser_navigate,mcp__browser__browser_snapshot,mcp__browser__browser_click,mcp__browser__browser_type,mcp__browser__browser_press_key,mcp__browser__browser_wait_for,mcp__browser__browser_tabs,mcp__browser__browser_navigate_back,mcp__browser__browser_select_option,mcp__browser__browser_hover
          --disallowedTools Bash,Edit,Write,NotebookEdit,WebFetch,WebSearch,mcp__browser__browser_evaluate,mcp__browser__browser_run_code_unsafe,mcp__browser__browser_file_upload
   ```
   - 프롬프트는 `src/lib/aiSearch.ts`의 `handoffPrompt`와 같은 규칙을 쓰되, 브라우저 안내만 "mcp__browser__ 도구"로 바꾼다.
   - 규칙: 구매·결제 금지, 비밀번호 입력 금지, 화면에서 본 것만, 마지막 줄 `파트너스매니저 {...}`.
   - `--permission-mode`는 `dontAsk`로 둔다 (허용 목록 밖은 묻지 않고 거절). 도움말의 선택지에 있다.
5. **진행·결과**
   - 서버: `POST /api/claude/search {q, cond}` → 작업 id, `GET /api/claude/job?id=` → 상태·진행 줄·최종 글.
   - stream-json의 `assistant` tool_use를 "쿠팡 검색 여는 중"처럼 사람이 읽을 말로 바꿔 보여 준다.
   - `result` 이벤트에서 결과 글, 실제 사용량(`total_cost_usd`·`usage`·`num_turns`)을 받는다.
   - 앱: 끝나면 결과 글을 `parseHandoff`로 검사한다. 파트너스 링크일 때만 보관함에 자동 저장한다 (기존 검사 그대로).
   - 한 번에 하나만 돌린다. 멈추기 버튼은 프로세스 트리를 끝낸다.
6. **설정 → Claude 연결**
   - `GET /api/claude/status`: `claude auth status --json` (`loggedIn`, `authMethod`). 이메일은 일부만 보인다.
   - `POST /api/claude/login`: 보이는 명령 창에서 `claude auth login --claudeai`를 연다 (로그인은 찰스님이 직접).
   - 설치 안내: `npm install -g @anthropic-ai/claude-code` (확인 창 뒤에만).
7. **보안**
   - 위 엔드포인트는 같은 출처(기존 Host·Origin 검사) + 사용자 정의 헤더(`X-PM: 1`)가 있을 때만 받는다. 다른 사이트에서는 사전 요청(preflight) 때문에 부를 수 없다.
   - 9335 디버깅 포트는 127.0.0.1에만 열린다. 이 PC의 다른 프로그램은 그 전용 Edge를 다룰 수 있다 — 설정 화면에 적어 둔다.
8. **사용량**: 쿠팡 페이지 스냅샷이 커서 검색 한 번에 토큰을 꽤 쓴다. 화면에 "Claude 사용량을 씀"을 적고, 끝나면 실측(`total_cost_usd`)을 보여 준다.

## PC에서 확인할 것 (이 클라우드 세션에서는 못 함)

- `claude auth status --json`이 찰스님 PC에서 `loggedIn: true`인지
- 전용 Edge + Playwright MCP 연결 (첫 `npx` 내려받기가 느림)
- 쿠팡이 자동 조작을 막지 않는지
- 파트너스 링크 만들기 화면에서 실제로 `https://link.coupang.com/a/…`가 나오는지
- 끝나면 `npm test`·`npx tsc --noEmit`·`npx expo lint`
- `npm run build:desktop` → `dist-desktop/PartnersManager`의 `install.bat`으로 다시 설치해 확인
