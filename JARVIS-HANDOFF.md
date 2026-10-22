# 자비스 인수인계: Claude Code 필수 스킬 5종

> 출처: 게으른 빌더(lazyowen.com) 「클로드 코드에 무조건 깔아야 하는 스킬 5개」 (2026-08-13 갱신본, PDF 11쪽)
> 목적: 아래 5개 스킬을 자비스가 설치·검증하고 바로 쓸 수 있도록 정리한 패키지입니다.

## 0. 한 줄 요약

```bash
cd <작업할 프로젝트 폴더>
unzip jarvis-skills.zip -d /tmp && /tmp/jarvis-skills/install.sh --with-upstream   # 계정 전체는 --global 추가
```

설치가 끝나면 **Claude Code를 완전히 종료했다가 다시 켜세요.** 재시작 전에는 새 스킬과 `/gsd-` 명령을 인식하지 못합니다.

---

## 1. 이 패키지에 들어 있는 것

```text
jarvis-skills.zip
└── jarvis-skills/
    ├── README.md                     ← 이 문서
    ├── install.sh                    ← 설치·검증 스크립트
    └── skills/
        ├── jarvis-agent-browser/SKILL.md
        ├── jarvis-find-skills/SKILL.md
        ├── jarvis-gsd/SKILL.md
        ├── jarvis-design-taste/SKILL.md
        └── jarvis-mcp-builder/SKILL.md
```

### 꼭 알아 둘 점: 두 종류의 스킬이 있습니다

| 구분 | 설명 |
|---|---|
| **`jarvis-*` 스킬 (이 zip에 들어 있음)** | PDF와 각 공식 문서를 바탕으로 **새로 작성한 안내 스킬**입니다. 설치 여부 확인 → 설치 → 검증 → 사용법 → 지켜야 할 것을 한곳에 모았습니다. |
| **공식 원본 스킬 (zip에 없음)** | 각 제작자가 만든 실제 스킬입니다. `install.sh --with-upstream` 또는 아래 3장의 명령으로 설치합니다. |

`jarvis-*` 스킬은 공식 원본을 **대체하지 않습니다.** 폴더 이름을 일부러 다르게 지어서(`jarvis-` 접두어) 원본과 이름이 충돌하거나 서로를 덮어쓰지 않고 함께 설치됩니다.

---

## 2. 5개 스킬 한눈에 보기

| # | 스킬 | 메우는 구멍 | 만든 곳 | 설치 방식 | 자비스용 스킬 |
|---|---|---|---|---|---|
| 1 | **agent-browser** | 만든 화면을 직접 열어 보지 못함 → 클릭·입력·캡처까지 직접 확인 | Vercel | `skills` 명령 + 별도 CLI | `jarvis-agent-browser` |
| 2 | **find-skills** | 어떤 스킬이 있는지 몰라 직접 다 만듦 → 공개 스킬을 찾아 추천·설치 | Vercel | `skills` 명령 | `jarvis-find-skills` |
| 3 | **GSD Core** | 긴 대화에서 앞의 말을 놓침 → 큰 작업을 단계로 쪼개고 기록 | open-gsd | **전용 설치 프로그램** | `jarvis-gsd` |
| 4 | **Taste** (`design-taste-frontend`) | 디자인이 매번 비슷함 → 기준을 세워 줌 | Leonxlnx | `skills` 명령 | `jarvis-design-taste` |
| 5 | **mcp-builder** | 쓰고 싶은 서비스를 Claude에 못 붙임 → 연결 서버를 만들고 검사 | Anthropic | `skills` 명령 | `jarvis-mcp-builder` |

다섯 개 모두 무료이며 서로 겹치지 않습니다.

---

## 3. 설치

### 방법 A. 스크립트 (권장)

```bash
cd <작업할 프로젝트 폴더>
unzip jarvis-skills.zip -d /tmp && /tmp/jarvis-skills/install.sh --with-upstream
```

| 옵션 | 동작 |
|---|---|
| (없음) | `jarvis-*` 5개만 `.claude/skills`에 복사 |
| `--global` | 계정 전체(`~/.claude`)에 설치 |
| `--with-upstream` | + 공식 원본 4개와 GSD 설치 (네트워크 필요) |
| `--with-browser` | + `agent-browser` 명령줄 도구와 자동화용 크롬 설치 (전역 npm 설치) |

스크립트의 약속:
- 이미 있는 폴더·파일은 **덮어쓰지 않고 건너뜁니다.**
- GSD 설치 전에 Claude Code 설정 파일이 있으면 `.bak-시각` 이름으로 **자동 백업**합니다.
- 마지막에 항상 **검증**하고, 하나라도 실패하면 완료로 보고하지 않고 종료 코드 1로 끝납니다.

### 방법 B. 수동 명령 (PDF 그대로)

작업할 프로젝트 폴더에서 한 줄씩 실행합니다. 계정 전체에 쓰려면 `npx` 줄 끝에 `-g`를 붙이고 GSD는 `--local` 대신 `--global`을 씁니다.

```bash
# 사전 확인
node --version                     # v22.20 이상
claude --version                   # 숫자가 나와야 함 (없으면 https://claude.com/claude-code)

# 1~4번: skills 명령 (--skill 로 하나만 고르는 것이 중요)
npx --yes skills@latest add vercel-labs/agent-browser --agent claude-code --yes --copy
npx --yes skills@latest add vercel-labs/skills --skill find-skills --agent claude-code --yes --copy
npx --yes skills@latest add https://github.com/Leonxlnx/taste-skill --skill design-taste-frontend --agent claude-code --yes --copy
npx --yes skills@latest add anthropics/skills --skill mcp-builder --agent claude-code --yes --copy

# 3번: GSD (설치 방식이 다름)
npx --yes @opengsd/gsd-core@latest --claude --global     # 프로젝트에만: --local, 가볍게: --profile=core 추가

# 1번의 실제 도구 (스킬은 안내문일 뿐, 브라우저를 움직이는 건 이 도구)
npm install -g agent-browser
agent-browser install                                     # 리눅스에서 실패하면: agent-browser install --with-deps
```

---

## 4. 설치 확인

눈으로 보지 말고 명령으로 확인합니다. 스킬은 조용히 설치되기 때문입니다.

```bash
ls .claude/skills                              # 프로젝트 설치. 계정 전체라면 ls ~/.claude/skills
npx --yes @opengsd/gsd-core@latest --help      # 버전 번호와 사용법이 나오면 GSD 설치됨
agent-browser --version                        # 버전 숫자가 나오면 브라우저 도구 준비 완료
```

`--with-upstream`까지 설치했다면 `ls .claude/skills`에 아래 9개가 보여야 합니다.

```text
agent-browser  design-taste-frontend  find-skills  mcp-builder          ← 공식 원본 4개
jarvis-agent-browser  jarvis-design-taste  jarvis-find-skills  jarvis-gsd  jarvis-mcp-builder   ← 자비스용 5개
```

이름이 하나라도 빠졌으면 그 줄만 다시 실행하세요. 이미 설치된 항목은 그대로 유지됩니다.
GSD는 Claude Code를 **완전히 종료 후 재시작**하고 대화창에 `/gsd-new-project`를 입력했을 때 무엇을 만들지 묻는 질문이 나오면 성공입니다. (이미 있는 프로젝트라면 `/gsd-onboard`)

---

## 5. 실제로 써 보기 (작은 요청으로 하나씩)

처음부터 큰 작업을 시키면 어디서 막혔는지 알기 어렵습니다. `<…>`는 상황에 맞게 바꿉니다.

**설치 상태만 확인**
```text
지금 이 프로젝트에 설치된 스킬 목록을 확인하고,
각각 무엇을 하는 도구인지 한 줄씩 정리해 주세요.
파일은 수정하지 마세요.
```

**브라우저**
```text
agent-browser로 <확인할 주소>를 열고 첫 화면을 캡처해 주세요.
버튼이 눌리는지, 링크가 실제로 이동하는지도 확인하고
문제가 있으면 어디가 문제인지 알려 주세요.
```

**스킬 탐색**
```text
<하고 싶은 작업>을 하려고 합니다.
쓸 만한 스킬이 이미 있는지 찾아보고,
설치 수와 만든 곳까지 같이 알려 주세요.
```

**디자인**
```text
<만들 화면 설명>을 만들어 주세요.
디자인 스킬의 기준을 따르고, 아래는 쓰지 마세요.
- 보라나 파랑 계열 그라데이션 배경
- 카드 안에 카드를 넣는 구조
- 튀기는 바운스 효과
```

**도구 연결**
```text
<연결하고 싶은 서비스>를 클로드에서 쓰고 싶습니다.
공식 API 문서는 <문서 주소>입니다.
MCP 서버를 만들어 주고, 다 만든 뒤 실제로 연결되는지 확인해 주세요.
```

---

## 6. 자비스에게 그대로 붙여넣을 프롬프트

PDF의 「설치부터 검증까지 AI에게 맡기기」 프롬프트를 이 패키지 기준으로 정리한 것입니다.

```text
역할: 클로드 코드 스킬 다섯 개의 설치와 연결 검증을 맡는 담당
맥락: 이 컴퓨터에는 다섯 개가 아직 설치되지 않았을 수 있고, 지금 클로드는 브라우저를 못 열고 디자인 기준도 없는 상태입니다.
입력: <운영체제와 셸, 예: macOS zsh>, <기술 스택, 예: Next.js 웹 프로젝트>, <목표 한 줄>
작업: 아래 단계를 순서대로 진행하세요.
1. 설치 여부 확인: `node --version`으로 22.20 이상인지 확인하고, `ls .claude/skills`와 `ls ~/.claude/skills`를 실행해 agent-browser, find-skills, design-taste-frontend, mcp-builder가 이미 있는지 확인하세요. `ls ~/.claude/commands/gsd-help.md`로 GSD 설치 여부도 확인하세요. 이미 있으면 다시 설치하지 말고 상태만 보고하세요.
2. 미설치 시 설치: 없는 것만 골라 설치하세요. 압축 해제한 jarvis-skills 폴더에서 `./install.sh --with-upstream`을 실행하면 됩니다(계정 전체는 `--global`). agent-browser 명령줄 도구가 필요하면 `--with-browser`를 추가하세요.
3. 연결 검증: `ls .claude/skills`를 실행해 네 이름이 모두 보이는지 확인하고, `npx --yes @opengsd/gsd-core@latest --help`가 버전 번호를 출력하는지 확인하세요. `agent-browser --version`으로도 확인하세요.
4. 마지막에 다섯 개가 각각 무엇을 해 주고 앞으로 어떻게 쓰면 되는지, 개발을 처음 하는 사람도 이해할 수 있는 말로 다섯 줄 이내로 설명하세요.
제약: 기존 파일과 설정을 덮어쓰거나 지우지 마세요. 이미 설치된 항목은 중복 설치하지 마세요. .env처럼 비밀키가 들어 있는 파일은 읽지도 출력하지도 마세요. 스킬 원본 파일을 임의로 수정하지 마세요.
출력: 실제로 실행한 명령어 목록, 확인된 Node 버전, 새로 생긴 폴더와 파일, 남아 있는 오류를 정리해 주세요.
검증: `ls .claude/skills`에 agent-browser, find-skills, design-taste-frontend, mcp-builder 네 개가 보이고, `npx --yes @opengsd/gsd-core@latest --help`가 버전 번호를 출력해야 합니다. 하나라도 실패하면 완료로 보고하지 말고 실패한 명령어와 원인, 다음 조치를 알려 주세요.
```

---

## 7. 지켜야 할 규칙

1. 이미 설치된 것은 다시 설치하지 않습니다. 기존 파일·설정을 덮어쓰거나 지우지 않습니다.
2. `.env` 같은 비밀키 파일은 읽지도 출력하지도 않습니다. 스크린샷에도 비밀값이 찍히지 않게 합니다.
3. 설치된 스킬 원본 파일을 임의로 수정하지 않습니다.
4. 저장소는 `owner/repo` 형태로 통째로 적고, 여러 스킬이 든 저장소는 **`--skill`로 하나만** 고릅니다.
5. 실패를 "완료"로 보고하지 않습니다. 실패한 명령어, 원인, 다음 조치를 그대로 알립니다.
6. 새 스킬을 설치할 때는 사용자 승인을 먼저 받습니다. 스킬은 Claude의 행동을 바꾸는 지침이고, 설치 도구도 "설치 전 검토하라, 전체 권한으로 실행된다"고 안내합니다.
7. 브라우저 확인 시 테스트용 값만 입력하고, 실제 계정·결제 정보는 입력하지 않습니다.

---

## 8. 자주 막히는 곳

| 증상 | 원인 | 해결 |
|---|---|---|
| `command not found: npx` | Node.js가 없음 | nodejs.org에서 LTS 설치 후 터미널 재시작 |
| 설치는 됐는데 클로드가 모름 | 재시작 전 | Claude Code를 완전히 종료했다가 다시 켜기 |
| 스킬을 찾을 수 없다고 나옴 | 저장소 주소를 빼고 이름만 적음 | `owner/repo` 형태로 통째로 적고 `--skill`로 이름 지정 |
| Taste 설치 후 비슷한 스킬이 여러 개 | 저장소를 통째로 설치함 | `--skill design-taste-frontend`로 하나만 다시 설치 |
| `/gsd-` 명령을 못 알아들음 | GSD 설치 안 됨 또는 재시작 전 | `npx @opengsd/gsd-core@latest --help`로 확인 후 Claude Code 재시작 |
| agent-browser가 브라우저를 못 엶 | 명령줄 도구 미설치 | `npm install -g agent-browser` 후 `agent-browser install` |
| 리눅스에서 브라우저 실행 실패 | 시스템 파일 부족 | `agent-browser install --with-deps` |

---

## 9. 이 패키지에 대해 확인한 것과 확인하지 못한 것

### 확인한 것 (격리된 빈 폴더에서 실제 실행)
- PDF의 `npx skills add` 4개 명령은 모두 그대로 동작하며, 설치 후 `.claude/skills`에 `agent-browser`, `design-taste-frontend`, `find-skills`, `mcp-builder` 네 폴더가 생겼습니다.
- GSD(`--claude --local`)는 Node 22.22에서 설치가 끝까지 완료되었고 `commands/gsd-help.md`가 생겼습니다.
- `install.sh`: 첫 설치, 재실행(전부 건너뜀), `--global`(임시 HOME), 잘못된 옵션, Node 버전 부족 시 실패 처리(종료 코드 1), `--with-upstream` 전체 설치와 재실행을 모두 실행해 확인했습니다. `jarvis-*`와 공식 원본 9개가 이름 충돌 없이 함께 설치되었습니다.

### PDF와 달라진 점 (확인 시점의 최신 상태)
- **GSD 최신판(v1.18.0)은 Node 24 이상을 요구한다고 경고(`EBADENGINE`)합니다.** PDF는 Node 22.20 이상이라고 안내합니다. 위 시험에서는 Node 22에서도 설치가 완료되었지만, 문제가 생기면 Node를 24 이상으로 올리세요. (PDF 화면 예시의 GSD 버전은 1.10.0)
- **GSD 설치는 Claude Code 설정 파일에 훅 여러 개를 추가합니다** (컨텍스트 모니터, `.env` 읽기 방지 등). PDF에는 없는 내용이라 `install.sh`가 설치 전에 설정 파일을 백업합니다.
- Taste 저장소는 현재 `design-taste-frontend` 한 개만 두고 나머지 스킬은 다른 저장소로 옮겼습니다. 그래도 `--skill design-taste-frontend` 지정은 그대로 유효합니다.
- 설치된 원본 `agent-browser` 스킬은 사용법 본문이 아니라 입구 역할이라, 실제 사용법은 `agent-browser skills get core`로 읽어야 합니다. `jarvis-agent-browser`에 반영했습니다.

### 확인하지 못한 것
- `install.sh --global`과 `--with-browser`(전역 npm 설치, 자동화용 크롬 다운로드)의 실제 업스트림 설치. `--global`은 임시 HOME에서 `jarvis-*` 복사까지만 시험했습니다.
- `npx skills find` 검색 결과. 시험 환경에서 `skills.sh` 접속이 막혀 있어 "No skills found"만 출력되었습니다. 실제 사용 환경에서는 인터넷 접속이 필요합니다.
- Claude Code 재시작 후 스킬 인식과 `/gsd-new-project` 실제 동작 (대화형 Claude Code 세션이 필요한 단계).
- macOS·Windows에서의 `install.sh` 동작 (bash 스크립트이며 Linux에서만 시험했습니다. Windows는 WSL 또는 방법 B를 쓰세요).
- 이후 업스트림 버전이 올라가 명령이 바뀌는 경우. 어긋나면 각 공식 문서가 맞습니다.

---

## 10. 공식 자료

| 이름 | 주소 |
|---|---|
| Agent Browser | agent-browser.dev · github.com/vercel-labs/agent-browser |
| Skills CLI / 스킬 검색 | github.com/vercel-labs/skills · skills.sh |
| GSD Core | opengsd.net · github.com/open-gsd/gsd-core |
| Taste Skill | tasteskill.dev · github.com/Leonxlnx/taste-skill |
| Anthropic 공식 스킬 | github.com/anthropics/skills |
| Claude Code 스킬 문서 | code.claude.com/docs/en/skills |
| Node.js | nodejs.org |
| 원본 가이드 | lazyowen.com/guides/claude-code-skills-5-0813 |
