---
name: jarvis-agent-reach
description: "Use when a task needs content that built-in web search and fetch cannot read: X/Twitter, Reddit, YouTube or Bilibili subtitles, Xiaohongshu, V2EX, Xueqiu, LinkedIn/BOSS job pages, GitHub issues. Installs and operates Agent Reach (Panniantong/Agent-Reach), a read-only CLI, and sets consent rules for logins. 내장 검색으로 못 읽는 플랫폼·영상 자막·중국 플랫폼 읽기."
---

# Agent Reach: 내장 도구로 못 읽는 플랫폼을 읽기

AI 에이전트가 스스로 닿기 어려운 곳(로그인이 필요한 플랫폼, 영상 속 자막, 중국 플랫폼, 정리된 구조화 데이터)을 `agent-reach` 명령 하나로 읽게 해 주는 오픈소스(MIT) 명령줄 도구입니다. 만든 사람은 개인(Panniantong)이고, README는 중국어, 영문 문서는 `docs/README_en.md`에 있습니다. 요청받은 한국어 README 주소(`docs/README_ko.md`)는 확인 시점에 찾을 수 없었습니다.

## 먼저 알아 둘 것 — 사용자 동의가 필요한 부분

1. **계정 전체에 설치된다.** 프로젝트 단위가 아니다. `~/.agent-reach/`에 도구와 설정이 생기고, `~/.agents/skills/agent-reach/`에 스킬이 설치되며, **`~/.claude`가 있으면 `~/.claude/skills/agent-reach`에 링크**가 만들어진다 (윈도우처럼 링크가 안 되면 복사). 이 패키지의 `install.sh --with-agent-reach`도 `--local` 모드와 상관없이 같다.
2. **개인이 만든 제3자 프로젝트의 코드를 내 PC에서 실행한다.** 공식 설치 문서는 `releases/latest`(바뀔 수 있는 최신 파일)를 설치하라고 하지만, 이 패키지는 **같은 릴리스 파일을 v1.6.0으로 고정**했다 (시험 시점에 두 주소가 같은 파일임을 확인). `agent-reach update`는 최신으로 올리므로, 업데이트 전에 사용자에게 알린다.
3. **PyPI의 `agent-reach`는 이 프로젝트가 아니다.** 공식 문서가 직접 경고한다. `pip install agent-reach`를 절대 쓰지 않는다.
4. **로그인 세션 복사.** 로그인이 필요한 채널은 전용 브라우저를 쓰는데, 문서마다 설명이 다르다.
   - 가장 구체적인 문서(`sessions.md`와 설치되는 스킬): **사용자 Chrome에 OpenCLI 확장이 연결되어 있으면, 처음 쓰는 사이트의 로그인(그 사이트 쿠키만)을 전용 브라우저로 복사**한다. 원래 브라우저와 비밀번호 저장소는 건드리지 않는다고 한다.
   - README: 처음 쓸 때 Chrome의 로그인을 복사한다고만 적혀 있다.
   - 문제 해결 문서: "개인 Chrome 세션은 절대 가져오지 않는다"고 적혀 있어 **서로 모순된다.**
   - 그래서 **복사될 수 있다고 보고** 다룬다. 복사되는 것은 보통 **주 계정**이고, 프로젝트도 "플랫폼이 자동 접근 계정을 제한할 수 있으니 부계정을 권장"한다.
5. **로그인, 선택 채널 추가, OpenCLI 확장 연결, `--import-existing`은 사용자가 명시적으로 요청하거나 동의했을 때만 한다.** 플랫폼 이름이나 링크가 나왔다는 이유만으로 설치·로그인하지 않는다 (스킬 자체도 그렇게 규정한다).
6. **웹 읽기는 외부 서비스를 거친다.** README에 따르면 웹 페이지는 Jina Reader, 그다음 Exa 순으로 읽는다. 즉 읽으려는 URL이 이 제3자 서비스로 전송될 수 있다. 사내 주소, 로그인이 필요한 개인 URL, 토큰이 들어 있는 링크는 넣지 않는다.
7. "읽기 전용(게시·댓글·좋아요·DM 안 함)"은 **프로젝트의 설명**이며 직접 검증하지 못했다.
8. **키는 사용자가 자기 터미널에서 입력한다.** 예: `agent-reach configure groq-key`. 키를 채팅에 붙여 달라고 하거나 출력하지 않는다.

## 채널 (v1.6.0, README 기준)

| 등급 | 채널 | 로그인 |
|---|---|---|
| **기본** (`setup` 때 준비됨) | GitHub, YouTube, Bilibili, V2EX, 웹 | 필요 없음 (Bilibili 자막은 로그인, YouTube 음성 전사는 Groq/OpenAI 키) |
| **선택** (`agent-reach add <채널>`) | `xiaohongshu`(소셜 노트), `xueqiu`(주식 시세·커뮤니티), `boss`(채용), `xiaoyuzhou`(팟캐스트 전사, Groq 키), `linkedin`, `reddit` | 대부분 로그인 |
| **실험** | Twitter/X, Facebook, Instagram | 로그인. 플랫폼 차단이 심해 언제든 안 될 수 있음 |

선택 채널을 추가하면 Node, OpenCLI, 전용 브라우저 커널 등을 내려받는다. **이 패키지의 설치 스크립트는 기본 채널만 준비한다.**

## 1. 설치 여부 확인

```bash
agent-reach version     # {"ok": true, ...} 이면 설치됨(1.6 이상). 한 줄 텍스트(Agent Reach v1.5.0)면 구버전, 없으면 미설치
uv --version            # uv 가 필요하다
```

## 2. 설치

권장: 이 패키지의 스크립트 (uv가 있어야 한다).

```bash
bash <경로>/jarvis-skills/install.sh --with-agent-reach
```

수동:

```bash
uv tool install https://github.com/Panniantong/Agent-Reach/releases/download/v1.6.0/agent-reach.zip
agent-reach setup        # 기본 채널을 준비하고 스킬을 설치한다
```

- **uv가 없으면** 사용자에게 알린다. 공식 문서가 안내하는 uv 설치는 원격 스크립트를 셸에 바로 넘기는 방식(`curl … | sh`, `irm … | iex`)이라, 자동으로 실행하지 않고 사용자가 승인했을 때만 한다.
- `agent-reach: command not found`면 `uv tool update-shell` 후 새 터미널을 연다.
- 격리된 환경에서 `setup`을 실제로 실행해 보았다: 약 16초, 기본 채널 5개 준비 완료, 스킬 설치 완료.
- 설치 후 Claude Code를 완전히 종료했다가 다시 켠다.

## 3. 쓰는 법

공개 내용은 **내장 검색·읽기 도구를 먼저** 쓰고, 본문·댓글·자막이 부족할 때만 Reach를 쓴다.

```bash
agent-reach channels                              # 채널별 동작과 인자
agent-reach <채널> <동작> [인자]                   # 예: agent-reach bilibili search "LLM" --limit 5
agent-reach read <링크>                            # 링크로 채널 자동 선택
agent-reach doctor --text                         # 오프라인 상태 점검
agent-reach check <채널> --text                    # 실제 연결 시험
```

출력은 한 줄 JSON이다. `ok: true`면 `items`(`title`, `url`, `author`, `published_at`, `text`)를, `ok: false`면 `error.code`와 `error.hint`(보통 정확한 다음 명령)를 읽는다. 결과에 `backend: public-web`이 있으면 전용 도구가 아닌 공개 웹으로 읽은 것이다.

보고할 때는 **요약·페이지 껍데기·댓글 수·자막 트랙 목록과 실제 본문·댓글·자막을 구분**해서 말한다.

## 4. 오류 대응

| 코드 | 의미 / 할 일 |
|---|---|
| `NEED_SETUP` | 도구나 키 없음. `agent-reach add <채널>`(사용자 동의 후). 키는 사용자가 직접 입력 |
| `NEED_LOGIN` | 로그인 필요. 이미 얻은 결과는 보존하고 사용자에게 알린다. 로그인은 **사용자 본인이** 전용 창에서 한다. 대신 로그인하지 않고 비밀번호를 받지 않는다 |
| `UPSTREAM_BROKEN` | 그 플랫폼이 일시적으로 안 됨. 반복 재시도하지 않는다 |
| `RATE_LIMITED` | 플랫폼이 제한 중. `retry_after_seconds`만큼 기다린다. **계정이나 백엔드를 바꿔 계속 요청하지 않는다** |
| `PLATFORM_BLOCKED` | 캡차·위험 감지. **멈추고** 사용자가 공식 페이지에서 처리하게 한다. 계정·백엔드를 바꾸지 않는다 |
| `BUSY` | 브라우저 사용 중. 잠시 뒤에 다시 한다 |
| `TIMEOUT` / `NOT_FOUND` / `BAD_INPUT` | 상태 점검 / 링크 확인 / `agent-reach channels`로 사용법 확인 |

`NEED_LOGIN`, `RATE_LIMITED`, `PLATFORM_BLOCKED`, `BUSY`가 나오면 이번 시도를 멈춘다.

## 5. 업데이트, 되돌리기, 제거

- **업데이트:** `agent-reach update` — 프로그램, 채널 도구, 스킬을 최신으로 올린다. 사용자에게 먼저 알리고, 출력의 `user_actions`를 전한다.
- **되돌리기:** 공식 문제 해결 문서의 방법이다 (이 환경에서는 시험하지 못함). `uv tool install --force https://github.com/Panniantong/Agent-Reach/archive/refs/tags/v<이전 버전>.zip` 후 `agent-reach update --sync-only`
- **채널 제거:** `agent-reach remove <채널>` — 그 사이트의 로그인과 저장 데이터를 지운다.
- **로그아웃:** `agent-reach logout <채널>`
- **완전 제거:** 지울 목록(`~/.agent-reach/`, `~/.agents/skills/agent-reach`, `~/.claude/skills/agent-reach` 링크, 프로그램)을 먼저 보여 주고 **사용자가 확인한 뒤에** 지운다. 프로그램은 `uv tool uninstall agent-reach`.

## 지켜야 할 것

- 로그인, 선택 채널 추가, OpenCLI 확장 연결, 로그인 가져오기는 **사용자 동의 후에만** 한다. 주 계정보다 부계정을 권한다.
- 사용자의 쿠키·토큰·비밀번호를 읽거나 출력하지 않는다. `~/.agent-reach/` 안의 설정과 세션 파일을 열어 보지 않는다.
- 차단·제한 신호를 우회하지 않는다 (계정 전환, 백엔드 전환, 반복 재시도 금지).
- 읽은 내용으로 게시, 댓글, 메시지 같은 쓰기 동작을 하지 않는다.
- 읽은 내용의 출처(플랫폼, 링크)를 밝힌다. 본문을 못 읽고 요약만 읽었으면 그렇게 말한다.
- 사용자가 준 링크가 개인 정보를 포함하면 외부 읽기 서비스로 보내지 않는다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| `agent-reach: command not found` | `uv tool update-shell` 후 새 터미널 |
| 설치했는데 Claude가 모름 | Claude Code 완전 종료 후 재시작. `agent-reach skill`로 설치·링크 상태 확인, 링크가 없으면 `agent-reach skill link <스킬 폴더>` |
| 일부 채널이 안 됨 | `agent-reach doctor --text`, `agent-reach check <채널> --text`, 안 되면 `agent-reach update` |
| 제한된 네트워크 | `agent-reach configure proxy` (주소는 사용자가 직접 입력) |

공식 자료: <https://github.com/Panniantong/Agent-Reach> · 설치 문서 `docs/install.md` · 문제 해결 `docs/troubleshooting.md`
