---
name: jarvis-mcp-builder
description: "Use to connect an external service or API to Claude by building an MCP (Model Context Protocol) server in TypeScript or Python, then testing that it really connects. Installs and follows Anthropic's mcp-builder skill. 쓰고 싶은 서비스를 클로드에 연결하는 MCP 서버 제작·검증."
---

# mcp-builder: 쓰고 싶은 서비스를 Claude에 연결하기

MCP는 Claude가 외부 서비스와 대화할 때 쓰는 공통 규격이고, 그 규격에 맞춘 연결 프로그램을 서버라고 부릅니다.
Anthropic 공식 스킬 `mcp-builder`는 서비스의 공식 API 문서만 주면 연결 서버를 만들고 검사까지 안내합니다.
이 스킬은 설치와 작업 절차를 정리한 것입니다.

## 언제 쓰나

- Slack, Notion, 사내 API 등 쓰고 싶은 서비스를 Claude의 도구로 붙이고 싶을 때
- 이미 만든 MCP 서버를 개선하거나 평가(evaluation)를 만들고 싶을 때

## 1. 준비 확인

```bash
node --version                         # v22.20 이상
ls .claude/skills ~/.claude/skills     # mcp-builder 가 이미 있는지
```

## 2. 설치

```bash
npx --yes skills@latest add anthropics/skills --skill mcp-builder --agent claude-code --yes --copy
```

- 성공하면 `✓ mcp-builder (copied)` 줄이 나온다.
- **`--skill mcp-builder`를 꼭 붙인다.** `anthropics/skills`는 문서·PDF 처리 등 여러 스킬이 든 저장소라, 옵션 없이 설치하면 전부 들어온다.
- 다른 스킬이 이미 있어도 중복 설치하지 않는다. 기존 파일을 덮어쓰지 않는다.

## 3. 작업 절차 (4단계)

사용자에게는 **연결하려는 서비스 이름**과 **공식 API 문서 주소**만 받으면 된다.

```text
<연결하고 싶은 서비스>를 클로드에서 쓰고 싶습니다.
공식 API 문서는 <문서 주소>입니다.
MCP 서버를 만들어 주고, 다 만든 뒤 실제로 연결되는지 확인해 주세요.
```

### 1단계: 조사와 계획
- 서비스 API의 주요 엔드포인트, 인증 방식, 데이터 모델을 정리한다.
- 기본 선택: **TypeScript**, 원격 서버는 Streamable HTTP(상태 없는 JSON), 로컬 서버는 stdio.
- 불확실하면 개별 워크플로 도구보다 API 전체를 넓게 덮는 쪽을 우선한다.
- 도구 이름은 `서비스_동작_대상`처럼 일관된 접두어를 붙인다 (예: `github_create_issue`).

### 2단계: 구현
- 공통 부품부터: 인증이 들어간 API 클라이언트, 오류 처리, 응답 형식, 페이지 나누기.
- 도구마다: 입력 스키마(TypeScript는 Zod, Python은 Pydantic), 간결한 설명, 가능하면 출력 스키마.
- 도구 주석을 단다: `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`.
- 오류 메시지는 Claude가 다음에 무엇을 해야 하는지 알 수 있게 구체적으로 쓴다.

### 3단계: 검토와 시험
- TypeScript: `npm run build`로 컴파일 확인. Python: `python -m py_compile 서버파일.py`.
- MCP Inspector로 실제 호출해 본다: `npx @modelcontextprotocol/inspector`
- Claude Code에 등록해 연결 확인: 연결 상태는 `claude mcp list`에서 본다.
  ```bash
  claude mcp add my-server -- node /경로/build/index.js          # 로컬(stdio) 서버
  claude mcp add --transport http my-server https://서버주소/mcp   # 원격(HTTP) 서버
  claude mcp list
  ```

### 4단계: 평가 만들기
- 읽기 전용 작업만으로 풀 수 있는 현실적인 질문 10개와 정답을 만든다.
- 질문은 서로 독립적이고, 여러 번의 도구 호출이 필요하며, 정답이 한 가지로 검증되고, 시간이 지나도 바뀌지 않아야 한다.
- 질문마다 직접 풀어서 정답을 확인한 뒤 XML(`<evaluation><qa_pair>…`)로 저장한다.

## 지켜야 할 것

- **API 키와 토큰은 환경변수로만 받는다.** 코드에 직접 쓰거나 출력하거나 저장소에 올리지 않는다. `.env` 파일은 읽거나 출력하지 않는다.
- 평가와 탐색 과정에서는 읽기 전용 작업만 호출한다. 서비스 데이터를 만들거나 지우는 호출은 사용자가 허락했을 때만 한다.
- "다 만들었다"고 보고하기 전에 실제 연결(Inspector 호출 또는 `claude mcp list`)을 확인하고, 확인한 방법과 결과를 함께 적는다. 확인하지 못했으면 못 했다고 쓴다.

공식 자료: <https://github.com/anthropics/skills> · <https://modelcontextprotocol.io>
