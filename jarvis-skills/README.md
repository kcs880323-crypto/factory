# 자비스 인수인계: Claude Code 스킬 10종 (필수 5종 + PPT 도구 5종)

> 출처 1: 게으른 빌더(lazyowen.com) 「클로드 코드에 무조건 깔아야 하는 스킬 5개」 (2026-08-13 갱신, PDF 11쪽)
> 출처 2: 써매(@ssumae.ai) 「PPT 늘 밤티라면 — 클로드로 발표용 장표 만드는 도구 5개」 (2026-10-21 공개, Notion)
> 목적: 위 두 자료의 도구 10개를 자비스가 설치·검증하고 바로 쓸 수 있도록 정리한 패키지입니다.

## 0. 한 줄 요약

```bash
cd <작업할 프로젝트 폴더>
unzip jarvis-skills.zip -d /tmp && /tmp/jarvis-skills/install.sh --with-upstream --with-ppt   # 계정 전체는 --global 추가
```

설치가 끝나면 **Claude Code를 완전히 종료했다가 다시 켜세요.** 재시작 전에는 새 스킬, 플러그인, MCP 서버, `/gsd-` 명령을 인식하지 못합니다.

필수 5종만 필요하면 `--with-upstream`만, PPT 도구만 필요하면 `--with-ppt`만 붙이세요. 옵션 없이 실행하면 안내 스킬 10개만 복사합니다.

---

## 1. 이 패키지에 들어 있는 것

```text
jarvis-skills.zip
└── jarvis-skills/
    ├── README.md                         ← 이 문서
    ├── install.sh                        ← 설치·검증 스크립트
    └── skills/
        ├── jarvis-agent-browser/SKILL.md        ┐
        ├── jarvis-find-skills/SKILL.md          │ 파트 A: 필수 5종
        ├── jarvis-gsd/SKILL.md                  │
        ├── jarvis-design-taste/SKILL.md         │
        ├── jarvis-mcp-builder/SKILL.md          ┘
        ├── jarvis-ppt-document-skills/SKILL.md  ┐
        ├── jarvis-ppt-master/SKILL.md           │ 파트 B: PPT 도구 5종
        ├── jarvis-ppt-frontend-slides/SKILL.md  │
        ├── jarvis-ppt-slidev/SKILL.md           │
        └── jarvis-ppt-powerpoint-mcp/SKILL.md   ┘
```

### 꼭 알아 둘 점: 두 종류가 있습니다

| 구분 | 설명 |
|---|---|
| **`jarvis-*` 스킬 (이 zip에 들어 있음)** | 두 자료와 각 공식 문서를 바탕으로 **새로 작성한 안내 스킬**입니다. 설치 여부 확인 → 설치 → 검증 → 사용법 → 지켜야 할 것을 한곳에 모았습니다. |
| **공식 원본 (zip에 없음)** | 각 제작자가 만든 실제 스킬·플러그인·서버입니다. `install.sh`의 `--with-upstream`, `--with-ppt` 또는 아래 수동 명령으로 설치합니다. |

`jarvis-*` 스킬은 공식 원본을 **대체하지 않습니다.** 폴더 이름에 `jarvis-` 접두어를 붙여서 원본과 이름이 충돌하거나 서로를 덮어쓰지 않습니다.

---

## 2. 파트 A — 필수 스킬 5종

### 2-1. 한눈에 보기

| # | 스킬 | 메우는 구멍 | 만든 곳 | 설치 방식 | 자비스용 스킬 |
|---|---|---|---|---|---|
| 1 | **agent-browser** | 만든 화면을 직접 열어 보지 못함 → 클릭·입력·캡처까지 직접 확인 | Vercel | `skills` 명령 + 별도 CLI | `jarvis-agent-browser` |
| 2 | **find-skills** | 어떤 스킬이 있는지 몰라 직접 다 만듦 → 공개 스킬을 찾아 추천·설치 | Vercel | `skills` 명령 | `jarvis-find-skills` |
| 3 | **GSD Core** | 긴 대화에서 앞의 말을 놓침 → 큰 작업을 단계로 쪼개고 기록 | open-gsd | **전용 설치 프로그램** | `jarvis-gsd` |
| 4 | **Taste** (`design-taste-frontend`) | 디자인이 매번 비슷함 → 기준을 세워 줌 | Leonxlnx | `skills` 명령 | `jarvis-design-taste` |
| 5 | **mcp-builder** | 쓰고 싶은 서비스를 Claude에 못 붙임 → 연결 서버를 만들고 검사 | Anthropic | `skills` 명령 | `jarvis-mcp-builder` |

### 2-2. 설치

**방법 A. 스크립트 (권장)**

```bash
cd <작업할 프로젝트 폴더>
unzip jarvis-skills.zip -d /tmp && /tmp/jarvis-skills/install.sh --with-upstream
```

| 옵션 | 동작 |
|---|---|
| (없음) | `jarvis-*` 10개만 `.claude/skills`에 복사 |
| `--global` | 계정 전체(`~/.claude`)에 설치 |
| `--with-upstream` | + 공식 원본 4개와 GSD 설치 (네트워크 필요) |
| `--with-browser` | + `agent-browser` 명령줄 도구와 자동화용 크롬 설치 (전역 npm 설치) |
| `--with-ppt` | + 파트 B의 PPT 도구 설치 (3장 참고) |

스크립트의 약속:
- 이미 있는 폴더·파일은 **덮어쓰지 않고 건너뜁니다.**
- GSD 설치 전에 Claude Code 설정 파일이 있으면 `.bak-시각` 이름으로 **자동 백업**합니다.
- 마지막에 항상 **검증**하고, 하나라도 실패하면 완료로 보고하지 않고 종료 코드 1로 끝납니다.

**방법 B. 수동 명령 (PDF 그대로)**

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

### 2-3. 설치 확인

눈으로 보지 말고 명령으로 확인합니다. 스킬은 조용히 설치되기 때문입니다.

```bash
ls .claude/skills                              # 프로젝트 설치. 계정 전체라면 ls ~/.claude/skills
npx --yes @opengsd/gsd-core@latest --help      # 버전 번호와 사용법이 나오면 GSD 설치됨
agent-browser --version                        # 버전 숫자가 나오면 브라우저 도구 준비 완료
```

`--with-upstream`까지 설치했다면 `ls .claude/skills`에 공식 원본 4개(`agent-browser`, `design-taste-frontend`, `find-skills`, `mcp-builder`)와 `jarvis-*` 폴더가 보여야 합니다. 이름이 하나라도 빠졌으면 그 줄만 다시 실행하세요. 이미 설치된 항목은 그대로 유지됩니다.
GSD는 Claude Code를 **완전히 종료 후 재시작**하고 대화창에 `/gsd-new-project`를 입력했을 때 무엇을 만들지 묻는 질문이 나오면 성공입니다. (이미 있는 프로젝트라면 `/gsd-onboard`)

### 2-4. 실제로 써 보기 (작은 요청으로 하나씩)

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

## 3. 파트 B — PPT 장표 도구 5종

클로드에게 "소개 PPT 만들어줘"라고 하면 흰 바탕에 검은 제목, 글머리표 세 줄짜리 장표가 나옵니다. 디자인을 못해서가 아니라 장표를 제대로 뽑아 주는 도구를 안 달아 줬기 때문입니다. 다섯 개 모두 오픈소스이고 기본 사용에는 API 키가 필요 없습니다.

### 3-1. 어떤 도구를 쓸까

다 설치할 필요는 없습니다. 만들 장표에 맞춰 고릅니다.

| 이런 상황이면 | 도구 | 결과물 |
|---|---|---|
| **처음이다.** 진짜 .pptx 파일을 받아 바로 고치고 싶다 | `document-skills` | .pptx |
| 보고서·문서를 **표·그래프 장표**로 바꾸고 싶다 | `PPT Master` | .pptx |
| 신메뉴·이벤트처럼 **광고 포스터 느낌** 발표 | `frontend-slides` | .html (웹 발표) |
| **글로 적어서** 웹에서 넘기며 발표하고 싶다 | `Slidev` | 웹 발표 (마크다운 → 화면) |
| **회사 양식 .pptx**가 이미 있다 | `PowerPoint MCP` | 같은 양식의 새 .pptx |

### 3-2. 한눈에 보기

| # | 도구 | 만든 곳 | 준비물 | 설치 | 자비스용 스킬 |
|---|---|---|---|---|---|
| 1 | **document-skills** | Anthropic (공식) | claude CLI | 플러그인 | `jarvis-ppt-document-skills` |
| 2 | **PPT Master** | hugohe3 (MIT) | Python 3.10+ | 플러그인 + `pip install` | `jarvis-ppt-master` |
| 3 | **frontend-slides** | zarazhangrui | claude CLI | 플러그인 | `jarvis-ppt-frontend-slides` |
| 4 | **Slidev** | slidevjs | Node.js 22.12+ | 새 폴더에서 `npm init slidev` | `jarvis-ppt-slidev` |
| 5 | **PowerPoint MCP** | GongRzhe | uv(uvx) | `claude mcp add` | `jarvis-ppt-powerpoint-mcp` |

### 3-3. 설치

**방법 A. 스크립트 (권장)**

```bash
cd <작업할 프로젝트 폴더>
unzip jarvis-skills.zip -d /tmp && /tmp/jarvis-skills/install.sh --with-ppt
```

`--with-ppt`가 하는 일: 플러그인 3개 설치(`document-skills`, `ppt-master`, `frontend-slides`), PPT Master 파이썬 부품 설치, PowerPoint MCP 서버 등록. Slidev는 설치할 것이 없습니다(새 발표를 만들 때 `npm init slidev`).
시스템 파이썬과 충돌하는 환경이면 가상환경을 만들고 `PPT_PYTHON=~/ppt-venv/bin/python ./install.sh --with-ppt`로 실행하세요.

**방법 B. 수동 명령**

Claude Code 대화창의 `/plugin …` 명령과 같은 일을 하는 터미널 명령이 `claude plugin …`입니다. 대화창에서 쓰려면 앞에 `/`를 붙여 한 줄씩 따로 입력합니다.

```bash
# 1. document-skills (xlsx, docx, pptx, pdf 스킬이 함께 설치됨)
claude plugin marketplace add anthropics/skills
claude plugin install document-skills@anthropic-agent-skills

# 2. PPT Master — 플러그인 + 파이썬 부품 (둘 다 필요)
claude plugin marketplace add hugohe3/ppt-master
claude plugin install ppt-master@ppt-master
REQ=$(ls -t "${CLAUDE_CONFIG_DIR:-$HOME/.claude}"/plugins/cache/ppt-master/ppt-master/*/ppt-master/requirements.txt | head -1)
python3 -m pip install -r "$REQ"

# 3. frontend-slides — HTTPS 전체 주소, 꺾쇠 없이
claude plugin marketplace add https://github.com/zarazhangrui/frontend-slides
claude plugin install frontend-slides@frontend-slides

# 4. Slidev — 빈 폴더에서 (질문에 답하며 진행하는 대화형 도구)
mkdir my-talk && cd my-talk && npm init slidev

# 5. PowerPoint MCP — 원본 가이드와 다름: --with 'mcp<2' 가 필요
claude mcp add ppt -- uvx --from office-powerpoint-mcp-server --with 'mcp<2' ppt_mcp_server
```

- 프로젝트에만 설치하려면 `--scope local`, 계정 전체는 `--scope user`(플러그인 기본값)입니다. MCP는 `claude mcp add --scope user ppt -- …`.
- 설치 후 Claude Code를 완전히 종료했다가 다시 켭니다.

### 3-4. 설치 확인

```bash
claude plugin list        # document-skills / ppt-master / frontend-slides 세 개가 enabled
claude mcp list           # ppt: … √ Connected
python3 -c "import pptx, yaml"   # PPT Master 부품 (가상환경이면 그 파이썬으로)
node --version            # Slidev: v22.12.0 이상
```

- `claude mcp list`에서 `Connected`가 아니라 `Failed to connect — CONNECTION_CLOSED`가 보이면 5번 명령에 `--with 'mcp<2'`가 빠진 것입니다.
- PPT Master가 작업 마지막에 멈추면 `pip install -r`을 빼먹은 것입니다.

### 3-5. 실제로 써 보기

`[…]` 안은 상황에 맞게 바꿉니다. **가격·별점·매출 같은 수치는 사용자가 준 자료만 쓰고, 자료가 없으면 지어내지 말고 자리표시자로 두라**고 자비스에게 알려 두세요.

**1. document-skills — 소개 PPT를 .pptx로**
```text
[가평 글램핑장] 소개 PPT 5장 만들어줘. 표지는 사진 꽉 차게, 객실 수·가격·별점은 카드로. pptx 파일로 저장해줘
```
성공: 작업 폴더에 `.pptx`가 생기고 파워포인트로 열어 그대로 고칠 수 있습니다.

**2. PPT Master — 문서를 그래프 장표로**
```text
이 문서로 [요가원 2026 연간 보고] 장표 10장 만들어줘. 분기별 회원 수는 막대그래프로
```
성공: 그래프·표가 들어간 .pptx가 돌아옵니다.

**3. frontend-slides — 화풍부터 고르는 웹 발표**
```text
/frontend-slides:frontend-slides [수제버거 신메뉴 «핫치킨 버거»] 출시 발표 6장. 광고 포스터 느낌으로
```
성공: 화풍 후보가 먼저 뜨고, 고르면 브라우저에서 ← → 로 넘기는 `.html`이 나옵니다.

**4. Slidev — 글을 쓰면 넘기는 발표**
```text
slides.md 를 [제주 2박 3일 여행 코스] 발표로 바꿔줘. 장마다 사진 배경, 코스는 번호가 하나씩 뜨게
```
성공: 브라우저에 발표 화면이 열리고, 글을 고치면 바로 바뀝니다.

**5. PowerPoint MCP — 회사 양식 그대로, 내용만**
```text
[가격표_회사양식.pptx] 를 열어서 [애견미용 2026 가격표] 로 내용만 바꿔줘. 색·로고·글꼴은 그대로
```
성공: 같은 양식에 내용만 바뀐 **새 파일**이 저장됩니다. (원본은 수정하지 않도록 안내합니다.)

**한 번에 쓰는 예시**
```text
document-skills 로 [우리 가게] 소개 PPT 6장 만들어줘. 표지는 사진 꽉 차게, 매출은 막대그래프, 마지막 장은 예약 안내
```

### 3-6. 원본 가이드와 다르게 해야 하는 부분 (직접 실행해서 확인한 것)

| 원본 가이드 | 실제로 확인한 내용 | 이 패키지의 처리 |
|---|---|---|
| PowerPoint MCP: `uvx --from office-powerpoint-mcp-server ppt_mcp_server` | 이 명령 그대로면 서버가 시작 직후 `ModuleNotFoundError`로 종료되어 `CONNECTION_CLOSED`. 패키지(2.0.7)가 `mcp` 라이브러리 버전을 고정하지 않아 최신 `mcp 2.x`(`FastMCP`가 `MCPServer`로 이름 변경)가 설치되기 때문 | `--with 'mcp<2'` 추가. 추가한 명령으로 `Connected` 확인 |
| frontend-slides: `/plugin marketplace add <https://github.com/…>` | 꺾쇠는 문서 서식이 남긴 것이라 명령에 넣으면 안 됨. 공식 README는 HTTPS 전체 주소를 쓰고 두 명령을 따로 입력하라고 안내(짧은 `owner/repo` 형태는 SSH로 시도하다 실패할 수 있음) | 꺾쇠 없는 HTTPS 주소 사용 |
| Slidev: "Node.js가 깔려 있어야 해요" | `create-slidev`와 `@slidev/cli` 모두 Node **22.12.0 이상** 요구 | 스킬에 버전 명시 |
| PPT Master: "설치된 폴더에서 `pip install -r requirements.txt`" | 설치 위치는 `~/.claude/plugins/cache/ppt-master/ppt-master/<버전 해시>/ppt-master/`로 업데이트마다 바뀜. 데비안 계열 시스템 파이썬에서는 `Cannot uninstall blinker … installed by debian`으로 실패하는 것을 실제로 확인 | 경로를 찾아서 설치. 실패 시 가상환경 안내 (`PPT_PYTHON`) |
| document-skills: "문서 스킬 설치" | 이 플러그인은 pptx만이 아니라 xlsx, docx, pdf 스킬 4개를 함께 설치 | 스킬에 명시 |
| "API 키도 필요 없어요" | 기본 사용은 맞음. 다만 PPT Master의 이미지 기능은 선택 사항으로 무료 키(`PEXELS_API_KEY`, `PIXABAY_API_KEY`)나 AI 이미지 생성용 공급자 키를 `.env`에 둘 수 있음 | `.env`는 읽거나 출력하지 않도록 규칙화 |

---

## 4. 자비스에게 그대로 붙여넣을 프롬프트

**파트 A (필수 5종)** — PDF의 「설치부터 검증까지 AI에게 맡기기」 프롬프트를 이 패키지 기준으로 정리한 것입니다.

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

**파트 B (PPT 도구 5종)**

```text
역할: 클로드 코드 PPT 장표 도구의 설치와 연결 검증을 맡는 담당
맥락: 클로드에게 PPT를 부탁하면 기본 틀의 밋밋한 장표만 나오는 상태입니다. 만들 장표 종류는 <소개 PPT / 보고서 그래프 장표 / 광고 느낌 웹 발표 / 마크다운 발표 / 회사 양식 수정 중 해당하는 것>입니다.
입력: <운영체제와 셸>, <Python 버전>, <Node 버전>
작업: 아래 단계를 순서대로 진행하세요.
1. 확인: `claude plugin list`, `claude mcp list`, `python3 --version`(3.10 이상), `node --version`(22.12 이상), `uvx --version`을 실행해 이미 설치된 것과 부족한 것을 보고하세요. 이미 있는 것은 다시 설치하지 마세요.
2. 설치: 압축 해제한 jarvis-skills 폴더에서 `./install.sh --with-ppt`를 실행하세요(계정 전체는 `--global`). 시스템 파이썬 충돌 오류가 나면 가상환경을 만들어 `PPT_PYTHON=<가상환경>/bin/python`으로 다시 실행하고 어느 가상환경인지 알려 주세요.
3. 검증: `claude plugin list`에 document-skills, ppt-master, frontend-slides가 보이고, `claude mcp list`에서 ppt가 Connected인지 확인하세요.
4. 설치 후 Claude Code를 완전히 종료했다가 다시 켜야 한다고 사용자에게 안내하고, 만들 장표 종류에 맞는 도구 하나를 추천해 사용 예시 한 줄을 알려 주세요.
제약: 기존 파일과 설정을 덮어쓰거나 지우지 마세요. 사용자의 원본 .pptx는 수정하지 말고 새 파일로 저장하세요. 가격·별점·매출 같은 수치는 사용자가 준 자료만 쓰고 지어내지 마세요. .env 파일은 읽지도 출력하지도 마세요.
출력: 실행한 명령어 목록, 설치된 항목, 남은 오류를 정리해 주세요.
검증: 위 3번이 모두 통과해야 합니다. 하나라도 실패하면 완료로 보고하지 말고 실패한 명령어와 원인, 다음 조치를 알려 주세요.
```

---

## 5. 지켜야 할 규칙

1. 이미 설치된 것은 다시 설치하지 않습니다. 기존 파일·설정을 덮어쓰거나 지우지 않습니다.
2. `.env` 같은 비밀키 파일은 읽지도 출력하지도 않습니다. 스크린샷에도 비밀값이 찍히지 않게 합니다.
3. 설치된 스킬 원본 파일을 임의로 수정하지 않습니다.
4. 저장소는 `owner/repo` 형태로 통째로 적고, 여러 스킬이 든 저장소는 **`--skill`로 하나만** 고릅니다. (플러그인 주소를 문서에서 복사할 때 꺾쇠 `<` `>`가 붙었다면 지웁니다.)
5. 실패를 "완료"로 보고하지 않습니다. 실패한 명령어, 원인, 다음 조치를 그대로 알립니다.
6. 새 스킬을 설치할 때는 사용자 승인을 먼저 받습니다. 스킬은 Claude의 행동을 바꾸는 지침이고, 설치 도구도 "설치 전 검토하라, 전체 권한으로 실행된다"고 안내합니다.
7. 브라우저 확인 시 테스트용 값만 입력하고, 실제 계정·결제 정보는 입력하지 않습니다.
8. **PPT·발표 자료의 수치와 사실은 사용자가 준 자료만 씁니다.** 가격, 별점, 매출, 일정을 지어내지 않고, 없으면 자리표시자로 두고 알립니다.
9. 사용자의 원본 .pptx·문서는 수정하지 않고 새 파일로 저장하며, 같은 이름의 파일을 덮어쓰지 않습니다.
10. 사진·이미지는 출처와 사용 가능 여부를 함께 알립니다.

---

## 6. 자주 막히는 곳

| 증상 | 원인 | 해결 |
|---|---|---|
| `command not found: npx` | Node.js가 없음 | nodejs.org에서 LTS 설치 후 터미널 재시작 |
| 설치는 됐는데 클로드가 모름 | 재시작 전 | Claude Code를 완전히 종료했다가 다시 켜기 |
| 스킬을 찾을 수 없다고 나옴 | 저장소 주소를 빼고 이름만 적음 | `owner/repo` 형태로 통째로 적고 `--skill`로 이름 지정 |
| Taste 설치 후 비슷한 스킬이 여러 개 | 저장소를 통째로 설치함 | `--skill design-taste-frontend`로 하나만 다시 설치 |
| `/gsd-` 명령을 못 알아들음 | GSD 설치 안 됨 또는 재시작 전 | `npx @opengsd/gsd-core@latest --help`로 확인 후 Claude Code 재시작 |
| agent-browser가 브라우저를 못 엶 | 명령줄 도구 미설치 | `npm install -g agent-browser` 후 `agent-browser install` |
| 리눅스에서 브라우저 실행 실패 | 시스템 파일 부족 | `agent-browser install --with-deps` |
| `/plugin` 명령이 안 됨 | Claude Code가 오래됨 | `claude update` 로 최신 버전으로 |
| 플러그인 설치했는데 안 보임 | 재시작 전 | Claude Code 완전 종료 후 재시작 |
| Windows에서 `npx`·`uvx`를 못 찾음 | Node.js / uv 미설치 | 설치 후 터미널을 새로 열기 |
| PPT Master가 마지막에 멈춤 | 파이썬 부품 미설치 | `pip install -r requirements.txt` (3-3의 2번) |
| PPT Master `pip` 실패 (`installed by debian` 등) | 시스템 파이썬 충돌 | 가상환경 + `PPT_PYTHON` |
| `claude mcp list`에 `CONNECTION_CLOSED` | `--with 'mcp<2'` 누락 | 3-3의 5번 명령으로 다시 등록 |
| frontend-slides 설치 중 SSH 오류 | 짧은 `owner/repo` 형태 사용 | HTTPS 전체 주소로 |

---

## 7. 이 패키지에 대해 확인한 것과 확인하지 못한 것

### 확인한 것 (격리된 빈 폴더·빈 HOME에서 실제 실행)
- **파트 A:** PDF의 `npx skills add` 4개 명령은 모두 그대로 동작하며 `.claude/skills`에 네 폴더가 생겼습니다. GSD(`--claude --local`)는 Node 22.22에서 설치가 끝까지 완료되었고 `commands/gsd-help.md`가 생겼습니다.
- **파트 B:** `claude plugin marketplace add` + `claude plugin install`로 세 플러그인(document-skills, ppt-master, frontend-slides)이 설치되어 `enabled`로 표시됨을 확인했습니다. PPT Master의 `requirements.txt`를 가상환경에 설치했고, 요구 패키지 19개를 모두 불러올 수 있음을 확인했습니다. PowerPoint MCP는 수정한 명령으로 `claude mcp list`에서 `Connected`가 되는 것까지 확인했습니다. 서버가 MCP `initialize` 요청에 정상 응답하는 것도 확인했습니다.
- **`install.sh`:** 첫 설치, 재실행(전부 건너뜀), 로컬·`--global` 범위, 잘못된 옵션, Node 버전 부족, claude CLI 없음, 파이썬 경로 오류, 시스템 파이썬 충돌, `--with-upstream`과 `--with-ppt` 동시 사용을 모두 실행했습니다. 실패 상황에서는 모두 `미완료` 목록과 종료 코드 1로 끝났습니다. `jarvis-*` 10개와 공식 원본이 이름 충돌 없이 함께 설치되었습니다.

### 파트 A: PDF와 달라진 점 (확인 시점의 최신 상태)
- **GSD 최신판(v1.18.0)은 Node 24 이상을 요구한다고 경고(`EBADENGINE`)합니다.** PDF는 22.20 이상이라고 안내합니다. 시험에서는 Node 22에서도 설치가 완료되었지만, 문제가 생기면 Node를 24 이상으로 올리세요.
- **GSD 설치는 Claude Code 설정 파일에 훅 여러 개를 추가합니다** (컨텍스트 모니터, `.env` 읽기 방지 등). `install.sh`가 설치 전에 설정 파일을 백업합니다.
- Taste 저장소는 현재 `design-taste-frontend` 한 개만 두고 나머지는 다른 저장소로 옮겼습니다. `--skill design-taste-frontend` 지정은 그대로 유효합니다.
- 설치된 원본 `agent-browser` 스킬은 입구 역할이라, 실제 사용법은 `agent-browser skills get core`로 읽어야 합니다.

### 확인하지 못한 것
- **PPT를 실제로 만들어 보는 단계.** 도구 설치와 연결까지만 확인했고, 장표 생성 결과(.pptx/.html)의 품질과 실제 동작은 대화형 Claude Code 세션이 필요해서 시험하지 못했습니다. `npm init slidev`도 대화형이라 실행하지 않았고, Node 요구 버전만 확인했습니다.
- `--with-browser`(전역 npm 설치, 자동화용 크롬 다운로드)의 실제 설치, `install.sh --global --with-upstream`(`--global --with-ppt`는 확인함).
- `npx skills find` 검색 결과. 시험 환경에서 `skills.sh` 접속이 막혀 "No skills found"만 출력되었습니다.
- Claude Code 재시작 후 스킬 인식과 `/gsd-new-project`, `/frontend-slides:frontend-slides` 실제 동작.
- macOS·Windows에서의 `install.sh` 동작 (bash 스크립트이며 Linux에서만 시험했습니다. Windows는 WSL 또는 수동 명령을 쓰세요).
- 이후 업스트림 버전이 올라가 명령이 바뀌는 경우. 어긋나면 각 공식 문서가 맞습니다. 특히 PowerPoint MCP는 패키지가 `mcp` 2.x에 대응하면 `--with 'mcp<2'`가 필요 없어질 수 있습니다.

---

## 8. 공식 자료

| 이름 | 주소 |
|---|---|
| Agent Browser | agent-browser.dev · github.com/vercel-labs/agent-browser |
| Skills CLI / 스킬 검색 | github.com/vercel-labs/skills · skills.sh |
| GSD Core | opengsd.net · github.com/open-gsd/gsd-core |
| Taste Skill | tasteskill.dev · github.com/Leonxlnx/taste-skill |
| Anthropic 공식 스킬 (mcp-builder, document-skills) | github.com/anthropics/skills |
| PPT Master | github.com/hugohe3/ppt-master |
| frontend-slides | github.com/zarazhangrui/frontend-slides |
| Slidev | github.com/slidevjs/slidev · sli.dev |
| PowerPoint MCP | github.com/GongRzhe/Office-PowerPoint-MCP-Server |
| Claude Code 스킬 문서 | code.claude.com/docs/en/skills |
| Node.js / uv | nodejs.org · docs.astral.sh/uv |
| 원본 가이드 1 | lazyowen.com/guides/claude-code-skills-5-0813 |
| 원본 가이드 2 | https://app.notion.com/p/PPT-5-3ff3401b7e0180f1912fd6d4daf16084 |
