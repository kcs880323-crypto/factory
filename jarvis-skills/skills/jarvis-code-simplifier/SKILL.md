---
name: jarvis-code-simplifier
description: "Use after code has been written or modified and it should be made clearer and more consistent without changing behavior: the official code-simplifier agent refines recently modified code. Installs the plugin and sets safeguards (tests, diff review). 방금 작성·수정한 코드를 동작은 그대로 두고 더 읽기 쉽게 정리."
---

# code-simplifier: 방금 고친 코드를 동작은 그대로 두고 정리하기

Anthropic 공식 플러그인 `code-simplifier`는 **에이전트**(`code-simplifier`) 하나를 제공합니다. 최근에 작성하거나 수정한 코드를 대상으로, **기능은 그대로 두고** 구조를 단순하게, 이름과 스타일을 일관되게, 유지보수하기 쉽게 다듬습니다.

## 먼저 알아 둘 것

1. **코드를 직접 수정한다.** 읽기 전용 점검 도구가 아니다. 그래서 아래 "안전장치"를 지킨다.
2. 에이전트 지침에는 **JavaScript/TypeScript·React 위주의 스타일 선호**가 들어 있다 (예: ES 모듈과 import 정렬, `function` 키워드 선호, 최상위 함수의 반환 타입 명시, 가능하면 try/catch 지양, 중첩 삼항 연산자 금지). 지침은 "프로젝트의 CLAUDE.md 표준을 따른다"고 하지만, **프로젝트의 실제 규칙과 어긋나는 변경이 들어올 수 있다.** 다른 언어나 다른 컨벤션의 저장소에서는 특히 결과를 꼼꼼히 본다.
3. 에이전트는 **적극적으로 동작하도록 쓰여 있다** ("요청이 없어도 코드를 쓴 직후 정리"). 사용자가 원하지 않는 시점에 변경이 생기지 않도록 범위를 정해 준다.
4. 사용 모델이 `opus`로 지정되어 있어 다른 에이전트보다 비용이 더 들 수 있다.

## 언제 쓰나

- 기능 구현이 끝나고 테스트가 통과한 뒤, 병합 전에 가독성을 정리하고 싶을 때
- 사람 리뷰 전에 중복·불필요한 복잡도를 줄이고 싶을 때

테스트가 없거나 실패 중인 코드, 커밋되지 않은 큰 작업물에는 쓰지 않는다.

## 1. 설치 여부 확인

```bash
claude plugin list        # code-simplifier@claude-plugins-official 가 보이는지
```

## 2. 설치

```bash
claude plugin marketplace add anthropics/claude-plugins-official     # 공식 마켓이 아직 없을 때만
claude plugin install code-simplifier@claude-plugins-official
```

Claude Code 대화창에서는 `/plugin install code-simplifier@claude-plugins-official`. 프로젝트에만 쓰려면 `--scope local`.
설치 후 Claude Code를 완전히 종료했다가 다시 켠다.

## 3. 사용 순서 (안전장치 포함)

1. **정리 전에 테스트를 돌려 통과 상태를 확인**하고 현재 변경을 **커밋하거나 최소한 diff를 저장**해 둔다. 되돌릴 수 있어야 한다.
2. 대상 범위를 지정해서 요청한다.
   ```text
   code-simplifier 에이전트로 방금 수정한 <파일/함수>만 정리해 줘.
   동작은 바꾸지 말고, 이 프로젝트의 CLAUDE.md 와 기존 코드 스타일을 따라 줘.
   ```
3. **정리 후 테스트를 다시 돌려** 결과가 같은지 확인한다.
4. **diff를 검토**한다. 동작이 바뀌었거나 프로젝트 스타일과 어긋나는 변경은 되돌린다.
5. 사용자에게 무엇이 바뀌었는지(파일, 요지)와 테스트 전후 결과를 보고한다.

## 지켜야 할 것

- **범위를 최근 수정한 코드로 제한한다.** 사용자가 명시하지 않으면 저장소 전체를 정리하지 않는다.
- 정리 전후 테스트 결과를 사실대로 보고한다. 테스트를 돌리지 못했으면 못 했다고 쓴다.
- 동작이 같다고 **단정하지 않는다.** 테스트가 덮지 않는 부분은 검증하지 못했다고 알린다.
- 프로젝트의 규칙(CLAUDE.md, 린터 설정)이 이 에이전트의 선호보다 우선한다.
- 설치된 플러그인 파일을 수정하지 않는다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| 에이전트가 목록에 없음 | Claude Code 완전 종료 후 재시작 |
| 프로젝트 스타일과 다르게 고쳐 놓음 | 해당 변경을 되돌리고, CLAUDE.md에 규칙을 명시한 뒤 범위를 좁혀 다시 요청 |
| 정리 후 테스트 실패 | 변경을 되돌리고 원인을 사용자에게 보고 |

공식 자료: <https://github.com/anthropics/claude-plugins-official> (플러그인 소개: `plugins/code-simplifier`)
