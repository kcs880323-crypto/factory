---
name: jarvis-gsd
description: "Use to run a large task in staged phases without losing context: install and operate GSD Core (@opengsd/gsd-core) with its Discuss, Plan, Execute, Verify, Ship loop and /gsd-new-project, /gsd-onboard commands. 큰 작업을 단계로 쪼개 진행, 긴 대화에서 길을 잃지 않게 관리."
---

# GSD Core: 긴 작업에서 길을 잃지 않게 잡아 주기

GSD(Git. Ship. Done.)는 큰 작업을 단계로 쪼개고, 결정한 내용을 파일로 남기고, 각 단계를 새 대화(깨끗한 컨텍스트)에서 실행하게 해 주는 도구입니다.
대화가 길어질수록 품질이 떨어지는 문제를 막는 것이 목적입니다.

> GSD는 다른 네 개와 설치 방식이 다릅니다. 스킬 파일만 넣는 게 아니라 명령어, 작업 기록 폴더, 훅까지 함께 설치하는 전용 설치 프로그램을 씁니다. **`agents/`나 `commands/` 파일을 직접 복사하지 않습니다.**

## 한 사이클 (페이즈마다 반복)

1. **Discuss**: 계획 전에 구현 결정을 먼저 기록
2. **Plan**: 조사하고 쪼개고, 새 컨텍스트에 들어가는 크기인지 검증
3. **Execute**: 계획을 병렬로 실행. 실행자마다 깨끗한 컨텍스트에서 시작
4. **Verify**: 만든 것을 직접 확인하고 문제를 진단해 고친 뒤에 완료 선언
5. **Ship**: PR 생성, 페이즈 보관, 다음 페이즈로

세션이 바뀌어도 `STATE.md`, `CONTEXT.md` 같은 파일이 남아서 이어서 진행할 수 있다.

## 1. 설치 여부 확인 (이미 있으면 다시 설치하지 않는다)

```bash
node --version                      # 22.20 이상 (아래 '버전 주의' 참고)
ls ~/.claude/commands/gsd-help.md   # 전체 설치 확인
ls .claude/commands/gsd-help.md     # 프로젝트 설치 확인
```

## 2. 설치

```bash
# 계정 전체
npx --yes @opengsd/gsd-core@latest --claude --global

# 지금 프로젝트에만
npx --yes @opengsd/gsd-core@latest --claude --local

# 설치 항목이 많다고 느껴지면 가벼운 구성
npx --yes @opengsd/gsd-core@latest --claude --global --profile=core
```

끝나면 `Done!`이 출력된다.

### 설치 전에 알아 둘 것

- **설정 파일에 훅이 추가된다.** 설치기가 Claude Code 설정 파일에 컨텍스트 모니터, 읽기 전 편집 방지, 비밀파일(.env) 읽기 방지 같은 훅을 등록한다. `--local`이면 `.claude/settings.local.json`에 기록되는 것을 확인했고, `--global`이면 보통 `~/.claude/settings.json`이 대상이다. 기존 설정을 지우지 않도록 **설치 전에 해당 설정 파일을 백업**하고, 설치 후 기존 항목이 남아 있는지 확인한다.
- **버전 주의.** 이 스킬을 만들 때 확인한 최신판(1.18.0)은 `node >= 24`를 요구한다고 경고(`EBADENGINE`)했지만, Node 22에서도 `--local` 설치는 끝까지 완료되었다. 경고가 보이면 숨기지 말고 사용자에게 알리고, 문제가 생기면 Node를 24 이상으로 올린다.
- 예전에 쓰이던 GSD 저장소 주소는 2026년 6월에 보관 처리되어 더는 갱신되지 않는다. 지금은 `@opengsd/gsd-core`만 쓴다.

## 3. 설치 후

1. **Claude Code를 완전히 종료했다가 다시 켠다.** 재시작 전에는 `/gsd-` 명령을 인식하지 못한다.
2. 대화창에서 시작한다.
   - 새 프로젝트: `/gsd-new-project` (무엇을 만들지 묻는 질문이 나오면 성공)
   - 이미 있는 프로젝트: `/gsd-onboard`
3. 슬래시 명령은 Claude Code 세션 안에서 사용자가 입력하는 것이다. 재시작이나 명령 입력을 직접 할 수 없는 환경이면 사용자에게 요청한다.

## 4. 설치 확인

```bash
npx --yes @opengsd/gsd-core@latest --help   # 버전 번호와 사용법이 나오면 설치된 것
```

- 이름·명령이 인식되지 않으면 위 명령으로 확인 후 Claude Code를 재시작한다.
- 그래도 안 되면 설치 명령을 다시 실행한다. 이미 설치된 항목은 유지된다.

## 지켜야 할 것

- 기존 설정과 파일을 덮어쓰거나 지우지 않는다. 설치 전 백업을 확인한다.
- `.env` 같은 비밀키 파일은 읽거나 출력하지 않는다.
- GSD가 만든 파일(`STATE.md`, `CONTEXT.md` 등)과 설치된 파일을 임의로 수정하지 않는다.
- 설치 실패를 "완료"로 보고하지 않는다. 실패한 명령, 원인, 다음 조치를 알린다.

공식 자료: <https://opengsd.net> · <https://github.com/open-gsd/gsd-core>
