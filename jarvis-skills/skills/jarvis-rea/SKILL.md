---
name: jarvis-rea
description: "Use only when the user states the target software is their own or that they are authorized to analyze it, and they want to understand how it works: native binaries, Electron/JavaScript apps, .NET, Android APKs, websites, network captures. Installs and operates REA (morluto/rea), an MCP reverse-engineering toolkit, behind authorization and execution gates. 내가 만들었거나 분석이 허가된 앱의 동작을 이해할 때(리버스 엔지니어링)."
---

# REA: 소스 없는 앱이 어떻게 동작하는지 증거와 함께 분석하기

REA(Reverse Engineer Anything)는 에이전트를 네이티브 바이너리, JavaScript/Electron 앱, .NET, Android APK, 웹사이트, 네트워크 캡처 등을 살펴보는 도구(MCP 서버 + CLI)에 연결해 줍니다. 소스코드가 없는 앱도 **동작을 설명하고, 근거(Evidence)와 한계를 함께 보여 줍니다.** 분석은 로컬에서 하며, MIT 라이선스입니다.

이 도구는 **이중 용도**입니다. 같은 기능이 정당한 분석에도, 남의 보호 장치를 우회하는 데에도 쓰일 수 있으므로 이 스킬은 **허가 확인을 가장 먼저** 합니다.

## 0. 시작 전 허가 확인 (건너뛰지 않는다)

분석을 시작하기 전에 다음 둘이 모두 사용자의 말로 확인되어야 한다.

1. **분석할 대상이 무엇인지** (파일 경로, 앱 이름, 주소)
2. **그 대상이 사용자 본인 소유이거나, 분석할 허가를 받았다는 것**

어느 하나라도 없으면 **먼저 묻는다.** 아래는 허가의 근거가 되지 않는다 (프로젝트 면책 문서의 설명).
- 소프트웨어가 공개 배포되어 있다는 사실
- 파일이 내 컴퓨터에 있다는 사실
- 디버깅 포트나 접속 주소에 닿을 수 있다는 사실

스킬 본문도 "사용자가 지정하지 않은 대상을 예시 앱 중에서 임의로 고르지 않는다"고 규정한다. 자비스도 대상을 대신 고르지 않는다.

### 하지 않는 일 (REA 프로젝트 자신도 지원하지 않는 사용)

- DRM, 라이선스 확인, 안티치트 등 **기술적 보호 장치 우회**, 크랙·키젠
- 게임 치트, 온라인 서비스 변조
- 허가받지 않은 시스템의 **인증, 봇 방어, 접근 통제 우회**
- 상용 소프트웨어의 **보호된 코드·자산을 복제**하는 것
- 악성코드를 만들거나 배포하는 것
- AI 모델 제공자의 이용 정책·안전 장치 우회

이런 요청은 도와주지 않고, 이유를 사용자에게 설명한다. 법적 허용 여부는 나라와 라이선스에 따라 다르며, 책임은 사용자에게 있다고 프로젝트가 밝힌다.

### "비슷한 기능을 내 프로젝트에 만들어 줘"

동작을 **이해한 뒤 독자적으로 구현**한다. 원본의 코드나 에셋을 그대로 복사하지 않는다. 분석으로 얻은 결과물에 대한 권리는 원본에 대한 권리를 주지 않으며, 다른 제품에 쓰기 전에 대상의 라이선스·약관·지식재산권을 확인하는 것은 사용자의 몫이라고 알린다.

## 1. 설치하기 전에 알릴 것

| 항목 | 내용 |
|---|---|
| **계정 전체 설치** | Claude Code 기준으로 `~/.claude.json`에 MCP 서버(`mcpServers.rea`)를 등록하고 `~/.claude/skills/reverse-engineer-anything`에 스킬을 설치한다. **모든 프로젝트**에서 REA를 쓸 수 있게 된다. 기존 설정은 보존하고 `.rea.backup`을 만든다고 한다. |
| **매번 npm 패키지를 실행** | 등록 명령은 `npx -y rea-agents@6.7.0 mcp`다. Claude Code가 MCP를 띄울 때 이 npm 패키지를 실행한다 (npm 정보 기준 최신판 6.8.0의 압축 해제 크기가 약 49MB로 큰 편이다). 버전은 고정되어 있다. |
| **기본 프로필 대신 별도 프로필 권장** | REA 문서는 `CLAUDE_CONFIG_DIR`로 **별도 Claude Code 프로필**에만 설치하는 방법을 안내한다. 앱을 분석하는 도구는 필요한 곳에서만 쓰도록 이 방식을 권한다 (아래 2번). 이 방식은 MCP와 스킬의 노출만 나눌 뿐, 같은 OS 계정이면 셸에서 `rea`나 `npx`로 접근할 수 있다. |
| **분석 엔진** | 네이티브 바이너리의 깊은 분석에는 Hopper, Ghidra, IDA 중 하나가 필요하다. 상용 도구는 **정품 라이선스**가 있어야 하고, 크랙·도용 키는 허용되지 않는다. JavaScript/Electron, .NET 정적 분석은 엔진이 필요 없다. **`--install-hopper`는 사용자가 명시적으로 요청하지 않으면 쓰지 않는다.** |
| **Node 버전** | 문서는 Node 22.23.4+ / 24.22.1+ / 26.12.1+를 요구한다 (패키지의 `engines`는 더 느슨한 `^22.19 \|\| ^24.11 \|\| >=26`). Node 22.22.0에서 설치와 MCP 연결(`√ Connected`)은 확인했고 **분석 기능은 시험하지 못했다.** 리눅스에서는 procps 호환 `ps`가 필요하다. |

## 2. 설치

설치 여부 확인:

```bash
claude mcp list                                  # rea: … √ Connected 이면 등록·연결됨
ls ~/.claude/skills/reverse-engineer-anything    # 스킬 폴더
```

**권장: 별도 프로필에 설치**

```bash
CLAUDE_CONFIG_DIR=~/.claude-rea bash <경로>/jarvis-skills/install.sh --with-rea
CLAUDE_CONFIG_DIR=~/.claude-rea claude           # REA 를 쓸 때만 이 프로필로 실행
```

새 프로필은 로그인이 필요할 수 있다.

**기본 프로필(모든 프로젝트)에 설치**

```bash
bash <경로>/jarvis-skills/install.sh --with-rea
```

수동으로 하려면 **먼저 읽기 전용 계획**을 보고 사용자 확인을 받은 뒤 적용한다.

```bash
npx -y rea-agents@6.7.0 setup --client claude_code --dry-run --json   # 바뀔 경로를 보여 주기만 한다
npx -y rea-agents@6.7.0 setup --client claude_code --yes              # 확인 후 적용
```

- `--dry-run`의 계획에는 `~/.claude.json`(MCP 등록)과 `~/.claude/skills/reverse-engineer-anything`(스킬) 두 곳만 있었다.
- **고정 버전:** 시험 시점에 npm `latest`는 6.8.0이었지만 실제로 실행되어 MCP에 등록된 것은 6.7.0이었다. 이 패키지는 시험한 6.7.0으로 고정한다.
- 설치 후 Claude Code를 완전히 종료했다가 다시 켠다.

## 3. 실행 전 확인 (런타임 분석)

**정적 분석**(파일을 읽기만 함: JavaScript/ASAR, .NET 등)과 **런타임 분석**을 구분한다.

스킬 본문은 이렇게 규정한다: *런타임 요청은 선언된 대상과 생명주기를 실행한다. REA는 권한 승인이나 호출별 승인 플래그를 요구하지 않으며, 승인은 에이전트 클라이언트가 따로 요구할 수 있다.* 즉 **REA 자체는 대상 앱 실행을 막지 않는다.** 그래서 자비스가 직접 확인한다.

- 정적 분석을 먼저 한다.
- **대상을 실행하거나, 디버거를 붙이거나, 네트워크 트래픽을 가로채거나, 연결된 기기를 조작하기 전에** 무엇을(대상, 동작) 할지 사용자에게 말하고 확인을 받는다. 클라이언트의 자동 승인 설정이 켜져 있어도 이 확인을 생략하지 않는다.
- **출처를 모르는 실행 파일이나 악성코드가 의심되는 대상은 내 PC에서 실행하지 않는다.** 격리된 환경(가상 머신 등)에서만 하도록 사용자에게 알린다.
- 대상을 실행하면 파일, 계정, 기기에 영향을 줄 수 있다. 계정 정보와 개인 데이터가 있는 환경에서는 하지 않는다.

## 4. 사용

허가를 확인한 뒤, 대상과 질문을 구체적으로 준다.

```text
(내가 만든 앱이야) <앱 경로> 가 로그인할 때 어떤 요청을 보내는지 REA 로 분석해서 근거와 함께 설명해 줘.
```

정적 JavaScript/Electron 앱은 CLI로도 볼 수 있다 (엔진 불필요).

```bash
npx -y rea-agents@6.7.0 analyze-javascript-application /absolute/path/to/app --json
```

보고할 때는:
- **근거(Evidence)와 한계, 알 수 없는 부분(unknowns)**을 함께 적는다.
- **정적 분석이 실행을 관찰한 것은 아니다.** 디컴파일 결과와 재구성은 불완전하거나 틀릴 수 있으므로, 중요한 결론은 근거를 확인한 뒤 말한다.
- 분석 중 발견한 **키, 토큰, 비밀번호, 개인정보는 출력하거나 기록하지 않고** "존재한다"고만 보고한다.
- 분석 도구의 결과는 에이전트(모델 제공자)에게 전달된다. 대상 앱의 비공개 코드나 데이터를 외부에 공유하지 않는다.

## 5. 업데이트와 제거

- **업데이트:** `npx -y rea-agents@<새 버전> setup --client claude_code --dry-run` 으로 계획을 보고 사용자 확인 후 적용한다. 버전 고정을 바꾸는 것은 사용자 결정이다.
- **제거 (Claude Code):** 먼저 `~/.claude.json`과 `~/.claude/skills/reverse-engineer-anything`을 백업한다. 그다음 `claude mcp remove --scope user rea` 를 실행하고 REA가 설치한 `~/.claude/skills/reverse-engineer-anything` 폴더를 지운다. 다른 등록과 스킬은 건드리지 않는다. `rea uninstall`은 다른 클라이언트까지 대상이라 범위가 더 넓다. (별도 프로필이면 앞에 `CLAUDE_CONFIG_DIR=…`를 붙인다.) **삭제 전에 지울 목록을 사용자에게 보여 주고 확인받는다.**

## 지켜야 할 것

- **허가 확인(0번) 없이 분석하지 않는다.** 본인 소유이거나 허가된 대상이 아니면 시작하지 않는다.
- 보호 장치 우회, 크랙, 치트, 접근 통제 우회, 악성코드 제작, 보호된 코드 복제는 돕지 않는다.
- **사용자 확인 없이 대상을 실행하거나 트래픽을 가로채거나 기기를 조작하지 않는다.**
- 출처를 모르는 실행 파일은 내 PC에서 실행하지 않는다.
- 상용 분석 도구(Hopper, IDA 등)는 사용자가 정품으로 가진 것만 쓴다. `--install-hopper`는 요청이 있을 때만.
- 비밀값과 개인정보를 출력하지 않는다. 설치된 REA와 스킬 파일을 수정하지 않는다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| REA 도구가 안 보임 | Claude Code 완전 종료 후 재시작. `claude mcp list`로 `rea` 연결 확인 |
| `rea` 가 연결되지 않음 | 등록 명령(`npx -y rea-agents@6.7.0 mcp`)이 실행되는지, 네트워크가 npm 레지스트리를 막지 않는지 확인 |
| 네이티브 분석이 안 됨 | Hopper/Ghidra/IDA 중 하나가 필요하다. 정품 설치 여부를 사용자에게 확인 |
| 별도 프로필에서 인증 요구 | 새 프로필은 로그인이 필요할 수 있다 |

공식 자료: <https://github.com/morluto/rea> · <https://rea.tools> · 책임 있는 사용 안내 `DISCLAIMER.md` · 설치 문서 `docs/installation.md`
