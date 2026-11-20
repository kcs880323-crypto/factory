---
name: jarvis-superpowers
description: "Use when the user wants Claude to follow a strict development workflow: brainstorm before building, written plans, test-driven development, systematic debugging, subagent-driven execution with reviews. Installs and manages the Superpowers plugin (obra/superpowers) and explains how it changes Claude's behavior. 개발 작업 방식(브레인스토밍→계획→TDD→리뷰)을 강제로 따르게 할 때."
---

# Superpowers: Claude의 개발 작업 방식을 통째로 바꾸는 플러그인

Superpowers는 브레인스토밍, 서브에이전트 기반 개발과 코드 리뷰, 체계적 디버깅, 레드/그린 TDD, 새 스킬 작성·테스트법을 Claude에게 가르치는 스킬 모음입니다.
공식 마켓에 올라 있지만 **Anthropic이 아니라 외부 개발자(obra)가 만든 것**입니다. 설치 버전은 마켓에 특정 커밋으로 고정되어 있습니다.

## 먼저 알아 둘 것: 이건 "도구"가 아니라 "행동 규칙"이다

일반 스킬과 달리, 이 플러그인은 **세션이 시작될 때마다(시작, `/clear`, 컨텍스트 압축 시) 훅이 스킬 사용 지침을 주입**합니다. 그 지침은 "스킬이 적용될 가능성이 1%라도 있으면 어떤 응답이나 행동보다 먼저 호출하라"고 강하게 요구합니다. 그 결과 다음이 달라집니다.

- "무엇을 만들자"고 하면 **먼저 브레인스토밍**(질문으로 요구사항 정리 → 설계 확인)을 한다. 바로 코드를 쓰지 않는다.
- 작업은 보통 설계 → 격리 작업공간(git worktree) → 작은 단위 계획 → 실행 → 리뷰 → 브랜치 마무리 순서로 진행한다.
- 구현 중에는 **TDD를 강제**한다 (실패하는 테스트 → 통과하는 최소 코드). 테스트 전에 쓴 코드는 지우도록 되어 있다.

즉 작은 수정에도 절차가 늘어나고 시간·토큰이 더 듭니다. **사용자가 원하는 개발 방식일 때만 켠다.**

## 이런 사람에게 맞다 / 맞지 않다

- 맞다: 새 기능·프로젝트를 설계부터 테스트까지 규율 있게 진행하고 싶을 때, 디버깅을 추측이 아닌 절차로 하고 싶을 때
- 맞지 않다: 한 줄 수정, 빠른 실험, 이미 다른 작업 방식을 쓰는 프로젝트

### `jarvis-gsd`와 같이 쓰지 않는다

GSD도 Discuss → Plan → Execute → Verify → Ship이라는 **자체 작업 방식**을 강제한다. 두 도구를 한 프로젝트에서 동시에 켜면 지침이 서로 겹치고 충돌한다. **프로젝트마다 하나만 고르고** 어느 쪽을 골랐는지 사용자에게 알린다.

## 1. 설치 여부 확인

```bash
claude plugin list        # superpowers@claude-plugins-official 가 보이는지
```

## 2. 설치

```bash
claude plugin marketplace add anthropics/claude-plugins-official     # 공식 마켓이 아직 없을 때만
claude plugin install superpowers@claude-plugins-official
```

Claude Code 대화창에서는 `/plugin install superpowers@claude-plugins-official`. 프로젝트에만 쓰려면 `--scope local`.
설치 후 Claude Code를 완전히 종료했다가 다시 켠다. 작업 방식 지침은 **새 세션부터** 적용된다.

> 설치 전에 사용자에게 위 "행동 규칙" 설명을 전하고 동의를 받는다. 외부 개발자의 코드가 세션마다 실행되는 훅을 포함하므로, 신뢰 여부는 사용자가 정한다.

## 3. 포함된 스킬 (설치된 7.0.0 기준)

`brainstorming`, `writing-plans`, `executing-plans`, `subagent-driven-development`, `test-driven-development`, `systematic-debugging`, `verification-before-completion`, `requesting-code-review`, `receiving-code-review`, `using-git-worktrees`, `finishing-a-development-branch`, `dispatching-parallel-agents`, `writing-skills`, `using-superpowers`, `diagnosing-superpowers`

스킬은 상황에 맞으면 **자동으로** 호출된다. 사용자가 따로 외울 명령은 없다.

## 4. 끄거나 지우기

```bash
claude plugin disable superpowers@claude-plugins-official      # 잠시 끄기 (설정 유지)
claude plugin enable superpowers@claude-plugins-official       # 다시 켜기
claude plugin uninstall superpowers@claude-plugins-official    # 삭제
```

세션이 너무 느려지거나 절차가 과하다고 느끼면 사용자에게 끄는 방법을 알려 준다. 끈 뒤에는 새 세션에서 적용된다.

## 지켜야 할 것

- **사용자의 요청을 절차로 가로막지 않는다.** 사용자가 "그냥 바로 고쳐 줘"라고 명시하면 그 요청을 따르고, 이 플러그인 때문에 생략한 절차(브레인스토밍, TDD 등)가 무엇인지 알린다.
- 설치된 플러그인 파일을 수정하지 않는다.
- 설치·활성화 상태를 보고할 때는 `claude plugin list` 출력을 근거로 적는다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| 설치했는데 동작이 같음 | Claude Code 완전 종료 후 **새 세션**에서 확인 |
| 작업마다 질문·절차가 너무 많음 | 의도된 동작. 필요 없으면 `disable` |
| GSD와 지침이 충돌함 | 둘 중 하나만 활성화 |

공식 자료: <https://github.com/obra/superpowers>
