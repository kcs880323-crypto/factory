---
name: jarvis-context7
description: "Use when code depends on a library's current, version-specific API and the model's memory may be outdated: fetch up-to-date documentation and examples through the Context7 MCP server (resolve-library-id, query-docs). Installs the context7 plugin and verifies the connection. 라이브러리 최신 문서·버전별 예제 조회, 오래된 API 사용 방지."
---

# context7: 최신 라이브러리 문서를 바로 끌어오기

Upstash의 Context7 MCP 서버에 연결해서 **라이브러리의 버전별 공식 문서와 코드 예제**를 가져옵니다. 모델이 알고 있는 오래된 API 대신 지금 버전의 문서를 근거로 코드를 쓰게 하려는 도구입니다.
공식 마켓에 올라 있지만 만든 곳은 Anthropic이 아니라 **Upstash**입니다.

## 먼저 알아 둘 것

1. **외부 서비스에 연결된다.** 이 플러그인은 **원격 서버(`https://mcp.context7.com/mcp`)** 에 붙는다. 조회할 라이브러리 이름과 질문이 그 서버로 전송된다. **사내 코드, 비밀값, 개인정보를 질문에 넣지 않는다.**
2. 기본은 **익명**으로 연결되고 익명 사용량 한도를 공유한다. 한도를 늘리려면 사용자가 Context7 대시보드에서 API 키를 발급받아 `CONTEXT7_API_KEY` 환경변수로 설정한다 (설정 후 Claude Code 재시작). **키를 대화나 파일에 출력하지 않는다.**
3. **그 서버로 나가는 네트워크가 허용되어야 한다.** 방화벽·프록시가 `mcp.context7.com`을 막으면 연결에 실패한다.

## 제공되는 도구

- `resolve-library-id`: 라이브러리를 검색해서 Context7용 식별자(예: `/vercel/next.js`)와 사용 가능한 버전 목록을 돌려준다.
- `query-docs`: 특정 라이브러리의 문서를 질문과의 관련도 순으로 가져온다.

특정 버전의 문서가 필요하면 식별자에 버전을 붙인다 (예: `/vercel/next.js/v15.1.8`). **프로젝트가 쓰는 버전**(`package.json`, `requirements.txt` 등)과 맞춰서 요청한다.

## 1. 설치 여부 확인

```bash
claude plugin list        # context7@claude-plugins-official 가 보이는지
claude mcp list           # plugin:context7:context7 ... Connected 인지
```

## 2. 설치

```bash
claude plugin marketplace add anthropics/claude-plugins-official     # 공식 마켓이 아직 없을 때만
claude plugin install context7@claude-plugins-official
```

Claude Code 대화창에서는 `/plugin install context7@claude-plugins-official`. 프로젝트에만 쓰려면 `--scope local`.
설치 후 Claude Code를 완전히 종료했다가 다시 켠다.

## 3. 연결 확인

```bash
claude mcp list
# plugin:context7:context7: https://mcp.context7.com/mcp?... (HTTP) - √ Connected
```

`Connected`가 아니고 `Failed to connect — ERR_PROXY_TUNNEL` 같은 오류가 보이면 **설치 문제가 아니라 네트워크 정책이 서버 접속을 막은 것**이다. 이 경우 완료로 보고하지 말고, 막힌 호스트(`mcp.context7.com`)와 허용 필요 사실을 사용자에게 알린다.

## 4. 사용

```text
Next.js 15 의 <기능> 사용법을 context7 로 확인해서, 지금 버전 기준으로 코드를 작성해 줘.
```

사용 흐름: `resolve-library-id`로 라이브러리와 버전을 찾는다 → 프로젝트 버전에 맞는 식별자를 고른다 → `query-docs`로 필요한 부분만 질문한다.

## 지켜야 할 것

- 질문에는 **라이브러리 이름과 일반적인 사용법**만 담는다. 프로젝트의 비공개 코드나 비밀값을 보내지 않는다.
- 문서에서 가져온 내용은 출처(라이브러리, 버전)와 함께 쓴다. 문서에 없는 내용을 있는 것처럼 말하지 않는다.
- 연결이 안 되는 상태에서 문서를 조회한 것처럼 보고하지 않는다.
- 설치된 플러그인 파일(`.mcp.json` 포함)을 수정하지 않는다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| `Failed to connect — ERR_PROXY_TUNNEL` | 네트워크 정책에서 `mcp.context7.com` 허용 필요 |
| 사용량 한도 오류 | 익명 한도. `CONTEXT7_API_KEY` 설정 후 재시작 |
| 설치했는데 도구가 안 보임 | Claude Code 완전 종료 후 재시작 |

공식 자료: <https://github.com/anthropics/claude-plugins-official> (`external_plugins/context7`) · <https://context7.com>
