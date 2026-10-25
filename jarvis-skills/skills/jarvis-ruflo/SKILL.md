---
name: jarvis-ruflo
description: "Use when the user wants Ruflo (formerly Claude Flow), a multi-agent orchestration layer for Claude Code with swarms, shared memory and 300+ MCP tools, or asks to install, check, configure or remove it. Installs a pinned npm version and the ruflo-core plugin, applies the safety settings (no auto-update, no command recording, no npx@latest fallback), and asks before anything that edits a project or starts background processes. 멀티 에이전트(Ruflo, 구 Claude Flow) 설치·점검·제거 안내."
---

# Ruflo: Claude Code에 멀티 에이전트·공유 메모리를 얹는 도구

`ruvnet/ruflo`(MIT, 구 Claude Flow). 설치하면 Claude Code에 **MCP 서버 하나(도구 379개: 에이전트, 스웜, 메모리, 훅 …)**, 범용 에이전트 4개(coder, researcher, reviewer, witness-curator), 스킬 5개(`ruflo-status`, `ruflo-doctor`, `init-project`, `discover-plugins`, `witness`), 그리고 **도구 호출마다 도는 훅**이 들어옵니다. 큰 작업을 에이전트 여러 개에게 나누고 세션을 넘어 기억하게 하는 용도입니다.

> **이 도구는 크고 부작용이 많아서 일부러 옵션(`--with-ruflo`)으로 따로 두었습니다.** 아래 0번을 사용자에게 알리고 동의받은 뒤에만 설치합니다.

## 0. 설치 전에 사용자에게 알릴 것 (직접 확인한 것)

| 항목 | 내용 |
|---|---|
| **크기** | 고정 버전 전역 설치가 **패키지 620개, 약 1.7GB, 설치에 약 2분** (npm 캐시가 약 0.6GB 더). 플러그인 마켓 저장소 복제도 약 0.2GB. |
| **과거 사고 (확인함)** | npm 패키지 `claude-flow`·`ruflo`의 **3.1.0-alpha.54, 3.1.0-alpha.55, 3.5.0, 3.5.2**(확인한 버전)에는 `preinstall` 스크립트가 있었고, 이 스크립트가 `~/.npm/_npx`의 숨김 임시 폴더와 `~/.npm/_cacache`에서 `claude-flow`·`ruflo`가 들어간 색인 파일을 **지웠다.** **3.5.3(2026-03-05)부터는 없고** 3.63.0에도 없다. 그래서 **오래된 버전을 설치하지 않고, `npx ruflo@latest`처럼 최신을 바로 실행하지도 않는다.** |
| **최신을 매번 실행하는 구조** | 플러그인의 훅은 `ruflo` 실행 파일이 없으면 `npx ruflo@latest`를, MCP 서버는 로컬 설치가 없으면 `npx -y @claude-flow/cli@latest`를 실행한다. 즉 **그때그때 npm의 최신 코드를 내려받아 돌린다.** 이 패키지는 **고정 버전을 전역 설치**해 이 경로를 막는다. |
| **자동 업데이트** | CLI가 시작할 때 하루 한 번 npm에 최신 버전을 물어보고, **패치 업데이트는 기본으로 자동 설치**한다 (`npm install <패키지>@<최신> --save-exact`를 **현재 폴더**에서 실행해 프로젝트의 `package.json`을 바꿀 수 있다). 고정이 풀리므로 `CLAUDE_FLOW_AUTO_UPDATE=false`로 끈다. |
| **도구를 쓸 때마다 도는 훅** | Bash, Write, Edit, MultiEdit 전후와 압축·종료 때 `ruflo`가 실행된다. **기본 설정이면 Bash 명령 문자열이 프로젝트 안의 `.swarm/agentdb-memory.db`와 `.claude-flow/neural/patterns.json`에 평문으로 저장된다** (비밀 값처럼 보이는 문자열로 시험해서 확인). `CLAUDE_FLOW_HOOKS_RECORD_COMMANDS=false`로 끈다. |
| **프로젝트에 파일이 생긴다** | 사용하면 프로젝트 루트에 `.swarm/`, `.claude-flow/`, `ruvector.db`(약 1.5MB)가 만들어진다 (`.gitignore`는 건드리지 않는다). 홈에는 `~/.claude-flow/`, `~/.config/ruflo/`가 생긴다. |
| **외부 전송 (기본은 꺼짐)** | 사용 통계(`funnel.ruv.io`)는 **동의를 해야만** 보낸다 (기본 동의 없음, 보내는 항목은 닫힌 목록이라 프롬프트·명령·경로는 못 담는다는 것을 코드에서 확인). 하지만 CLI에는 계정 로그인, 클라우드 라우팅, 학습 데이터 공유, 수익 분배 등록 같은 **동의 항목이 12가지** 있다. **자비스는 어느 것에도 동의하지 않는다.** |
| **여러 겹의 작업 방식 강제** | `superpowers`, GSD와 같은 종류의 도구다. **같은 프로젝트에서 같이 켜지 않는다.** |
| **라이선스·관리** | MIT. 저장소·npm 모두 한 개인(ruvnet)이 관리한다. 매일 새 버전이 나오는 빠른 프로젝트라 고정 버전을 쓴다. |

## 1. 설치 여부 확인과 설치

```bash
ruflo --version                  # 있으면 설치됨 (고정 버전: v3.63.0)
claude plugin list               # ruflo-core@ruflo 가 enabled 이면 플러그인도 설치됨
```

```bash
bash <경로>/jarvis-skills/install.sh --with-ruflo        # 계정 전체는 --global 추가
```

이 옵션이 하는 일 (이미 있으면 건너뛴다):

1. **`npm install -g ruflo@3.63.0`** (고정). 같은 이름의 다른 버전이 이미 있으면 **바꾸지 않고** 알린다.
2. 마켓 `ruvnet/ruflo`를 **`#v3.63.0` 태그**(커밋 `e2dae2f7…`)로 등록하고 플러그인 **`ruflo-core`만** 설치. 마켓에는 47개 플러그인이 있지만 나머지(swarm, federation, music, trader 등)는 **사용자가 필요하다고 한 것만** 따로 설치한다.
3. **안전 설정 3개**를 Claude Code 설정 파일(`--global`이면 `~/.claude/settings.json`, 아니면 이 프로젝트의 `.claude/settings.local.json`)의 `env`에 추가 (먼저 백업, 이미 있는 키는 그대로 둠):

| 키 | 값 | 효과 |
|---|---|---|
| `CLAUDE_FLOW_AUTO_UPDATE` | `false` | 시작 때 자동 업데이트(`npm install`)를 하지 않는다 |
| `CLAUDE_FLOW_HOOKS_RECORD_COMMANDS` | `false` | Bash 명령을 기록하지 않는다 (이 경우 훅은 Bash 이벤트에서 CLI를 시작조차 하지 않는다) |
| `RUFLO_HOOK_SKIP_NPX` | `1` | 훅이 `npx ruflo@latest`로 넘어가지 않는다 (고정 설치만 쓴다) |

수동 설치:

```bash
npm install -g ruflo@3.63.0
claude plugin marketplace add "ruvnet/ruflo#v3.63.0" --scope user
claude plugin install ruflo-core@ruflo --scope user
# 그리고 위 3개 키를 설정 파일의 "env" 에 추가
```

설치 후 Claude Code를 완전히 종료했다가 다시 켠다. Node 20 이상이 필요하다.

## 2. 쓰기

- 상태 확인: `/ruflo-core:ruflo-status` (플러그인이 붙이는 접두어가 있다). MCP 도구는 `mcp__plugin_ruflo-core_ruflo__…` 이름으로 보인다.
- 큰 작업을 나눌 때는 사용자가 원하는 일을 말하고 "ruflo의 swarm 도구로 에이전트 여러 개에게 나눠서 해 줘"라고 한다. 쓸 도구와 에이전트 수를 사용자에게 먼저 알린다.
- 다른 ruflo 플러그인이 필요해 보이면(`discover-plugins` 스킬이 추천한다) **이름과 하는 일, 위험(외부 연결, 배경 프로세스)을 알리고 승인받은 뒤** 하나씩 설치한다. 설치 명령은 `claude plugin install <이름>@ruflo --scope user` (마켓은 이미 고정 태그로 등록되어 있다).

## 3. 사용자 확인이 필요한 것 (스킬이 시키더라도 먼저 묻는다)

ruflo의 스킬은 `npx @claude-flow/cli@latest …`를 실행하라고 되어 있다. **이 패키지에서는 `npx …@latest` 대신 고정 설치된 `ruflo` 명령을 쓴다** (예: `ruflo doctor`). 그리고 다음은 **실행 전에 무엇이 바뀌는지 알리고 승인받는다.**

| 명령 | 바뀌는 것 |
|---|---|
| `ruflo init` / `init-project` 스킬 | 프로젝트에 **`CLAUDE.md`, `.claude/settings.json`, `.claude-flow/`를 만든다.** 이미 있는 `CLAUDE.md`와 설정을 건드릴 수 있다 |
| `ruflo doctor --fix`, `config reset`, `memory init --force` | 설정 초기화, 메모리 DB 재생성 (기존 기억이 사라질 수 있다) |
| `ruflo daemon start` | **배경 프로세스**를 시작한다 |
| `ruflo auth login`, `proxy …`, `funnel …`, 동의(consent) 항목 | 계정, 클라우드 라우팅, 학습 데이터 공유, 사용 통계. **자비스는 하지 않는다** |
| 다른 ruflo 플러그인 설치 | 위 2번 |

- 프로젝트마다 `.gitignore`에 `.swarm/`, `.claude-flow/`, `ruvector.db`를 넣을지 사용자에게 묻는다 (기억 DB와 학습 기록이 저장소에 올라가지 않게).
- 설정 파일의 `env`를 사용자가 바꿨다면 존중한다. 안전 설정 3개를 사용자 동의 없이 끄지 않는다.

## 4. 점검과 제거

```bash
ruflo --version
claude plugin list
```

제거 (사용자 확인 후):

```bash
claude plugin uninstall ruflo-core@ruflo --scope user
claude plugin marketplace remove ruflo
npm uninstall -g ruflo
# 설정 파일의 env 에서 위 3개 키 삭제 (사용자가 직접 넣은 같은 키는 두고 물어본다)
# 프로젝트의 .swarm/ .claude-flow/ ruvector.db 와 홈의 ~/.claude-flow, ~/.config/ruflo 는 목록을 보여 주고 삭제 확인을 받는다
```

## 지켜야 할 것

- 설치 전에 0번을 알리고 동의를 받는다. 오래된 버전, `npx ruflo@latest`, `npx @claude-flow/cli@latest`를 직접 실행하지 않는다.
- 안전 설정 3개(자동 업데이트 끔, 명령 기록 끔, npx 대체 끔)를 유지한다.
- 계정 로그인, 클라우드 라우팅, 학습 데이터 공유, 사용 통계 동의를 하지 않는다. 비밀 값(토큰, 비밀번호)을 명령 줄에 직접 쓰지 않는다 (기록 기능을 사용자가 켠 경우를 대비).
- `init`, `doctor --fix`, `config reset`, `memory init --force`, `daemon start`는 먼저 묻는다.
- 결과를 보고할 때 확인한 것(설치, 도구 목록, 훅 동작)과 확인하지 못한 것(실제 스웜 작업)을 구분한다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| `npm install -g`가 권한 오류 | 전역 폴더 권한 문제. `sudo`를 쓰기 전에 사용자에게 알리고, 가능하면 npm 전역 폴더를 사용자 폴더로 바꾸는 방법을 안내 |
| 다른 버전의 `ruflo`가 이미 있음 | 바꾸지 않고 알린다. 사용자가 원하면 `npm install -g ruflo@3.63.0`으로 맞추고 **재시작** |
| `ruflo` 명령이 안 나옴 | npm 전역 `bin` 폴더가 PATH에 있는지 확인 (`npm prefix -g`). 윈도우는 새 터미널을 연다 |
| 스킬·MCP가 안 보임 | Claude Code를 완전히 종료했다가 다시 켠다 |
| 마켓 등록이 이미 되어 있어 태그가 안 바뀜 | `claude plugin marketplace remove ruflo` 뒤 다시 등록 (사용자 확인 후) |
| 프로젝트에 낯선 `.swarm/`이 생김 | 정상 (0번). `.gitignore`에 넣을지 묻는다 |

시험한 범위: 격리된 HOME과 npm 전역 폴더에서 `npm install -g ruflo@3.63.0`(620개 패키지, 1.7GB, 약 2분, `ruflo v3.63.0`), 마켓을 `#v3.63.0`으로 등록하고 `ruflo-core` 0.2.11 설치, **설치된 플러그인의 MCP 서버를 표준입출력으로 실행해 초기화와 도구 목록(379개)을 받음**, 훅 실행(종료 코드 0, `{"permission":"allow"}`), 기본 설정에서 명령이 DB에 저장되고 안전 설정 3개를 켜면 저장되지 않으며 파일도 안 생기는 것, 고정 설치가 있으면 MCP 서버와 훅이 `npx`를 부르지 않는 것(가짜 `npx`로 감시), 자동 업데이트·사용 통계 코드와 `postinstall`(네트워크·삭제·실행 호출 없음)을 읽음. **시험하지 못한 것: 실제 스웜·에이전트 작업, 다른 ruflo 플러그인 46개, 윈도우, 오래 켜 둔 상태의 동작, 외부 연결 기능(IPFS, 클라우드 등)의 안전성.**

공식 자료: <https://github.com/ruvnet/ruflo> · npm `ruflo`, `@claude-flow/cli`
