---
name: jarvis-ppt-powerpoint-mcp
description: "Use when an existing company-template .pptx must be opened and edited in place while keeping its colors, logo and fonts, via the Office-PowerPoint-MCP-Server. Registers the MCP server and verifies it connects. 회사 양식 .pptx를 그대로 열어 내용만 바꿀 때, PowerPoint MCP 서버 연결."
---

# PowerPoint MCP: 회사 양식을 그대로 두고 내용만 바꾸기

"회사 양식 무시, 색 제각각"을 막아 주는 도구입니다. 이미 있는 .pptx를 클로드가 직접 열어서 우리 색, 로고, 글꼴을 지키며 고칩니다.
MCP 서버(Office-PowerPoint-MCP-Server)를 Claude Code에 연결해서 씁니다.

## 언제 쓰나

- 가격표, 보고서 양식처럼 **이미 만들어진 .pptx**가 있고 내용만 바꿀 때
- 새로 만드는 것이 목적이면 `jarvis-ppt-document-skills` 또는 `jarvis-ppt-master`가 맞다.

## 1. 준비 확인

```bash
uvx --version                 # uv가 필요하다. 없으면 https://docs.astral.sh/uv/ 에서 설치
claude mcp list               # 이미 ppt 서버가 있는지
```

이미 `ppt`가 연결되어 있으면 다시 등록하지 않는다. Windows에서 `uvx`를 못 찾으면 uv 설치 후 터미널을 새로 연다.

## 2. 연결 — 가이드의 명령에서 한 부분이 다르다

```bash
claude mcp add ppt -- uvx --from office-powerpoint-mcp-server --with 'mcp<2' ppt_mcp_server
```

**`--with 'mcp<2'`가 꼭 필요하다.** 이 서버 패키지(2.0.7 확인)는 `mcp` 라이브러리 버전을 고정하지 않는데, 최신 `mcp 2.x`에서는 `FastMCP`가 `MCPServer`로 이름이 바뀌어 서버가 시작 직후 `ModuleNotFoundError`로 종료된다. 이렇게 되면 `claude mcp list`에 `Failed to connect — CONNECTION_CLOSED`로 보인다. 위 명령은 이 문제를 피한 것이고, 연결되는 것까지 확인했다.

- 계정 전체에서 쓰려면 `claude mcp add --scope user ppt -- …`, 이 프로젝트에만 쓰려면 기본값(local)이다.
- 등록 후 **Claude Code를 껐다가 다시 켠다.**

## 3. 연결 확인

```bash
claude mcp list
# ppt: uvx --from office-powerpoint-mcp-server --with mcp<2 ppt_mcp_server - √ Connected
```

`Connected`가 보이면 성공이다. 실패하면 서버를 직접 실행해서 원인을 본다.

```bash
uvx --from office-powerpoint-mcp-server --with 'mcp<2' ppt_mcp_server < /dev/null
```

(첫 실행은 패키지 내려받기 때문에 시간이 걸린다. 파이썬 오류가 나오면 그 메시지를 사용자에게 그대로 알린다.)

## 4. 사용

```text
[가격표_회사양식.pptx] 를 열어서 [애견미용 2026 가격표] 로 내용만 바꿔줘. 색·로고·글꼴은 그대로
```

성공하면 같은 양식에 내용만 바뀐 **새 파일**이 저장된다.

## 지켜야 할 것

- **원본 .pptx는 수정하지 않는다.** 항상 복사본이나 새 이름으로 저장하고, 저장 경로를 알린다. 원본 덮어쓰기는 사용자가 명시적으로 요청했을 때만 한다.
- 가격, 날짜, 상호 같은 내용은 사용자가 준 것만 넣는다. 지어내지 않는다.
- 색, 로고, 글꼴이 실제로 유지되었는지 결과 파일을 확인하고, 확인하지 못했으면 확인하지 못했다고 적는다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| `Failed to connect — CONNECTION_CLOSED` | `--with 'mcp<2'`가 빠졌는지 확인, 3단계의 직접 실행으로 오류 확인 |
| `uvx: command not found` | uv 설치 후 터미널 새로 열기 |
| 서버가 안 보임 | Claude Code 완전 종료 후 재시작 |

공식 자료: <https://github.com/GongRzhe/Office-PowerPoint-MCP-Server>
