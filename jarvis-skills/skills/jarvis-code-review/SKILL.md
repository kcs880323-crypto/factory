---
name: jarvis-code-review
description: "Use when the user asks for an automated review of a GitHub pull request with the official code-review plugin (/code-review): parallel review agents, confidence scoring, and a comment posted on the PR. Covers install and the rule to confirm before posting. 풀 리퀘스트 자동 코드 리뷰, 신뢰도 점수로 거짓 경보를 걸러 PR에 댓글로 남김."
---

# code-review: PR 자동 리뷰 (결과를 PR에 댓글로 게시한다)

Anthropic 공식 플러그인 `code-review`는 `/code-review` 명령 하나로 풀 리퀘스트를 여러 에이전트가 나눠 검토하게 합니다.
CLAUDE.md 준수 여부, 눈에 띄는 버그, git 이력에 비춘 문제를 따로 살피고, 각 문제에 0~100점의 **신뢰도**를 매겨 **80점 미만은 버립니다.** 거짓 경보를 줄이기 위한 설계입니다.

## 먼저 알아 둘 것

1. **이 명령은 결과를 GitHub PR에 댓글로 게시한다 (`gh pr comment`).** 외부에 보이는 행동이므로 **실행 전에 사용자에게 대상 PR을 확인받는다.** "리뷰해 줘"가 "PR에 댓글을 달아도 된다"는 뜻인지 불분명하면 먼저 묻는다.
2. **GitHub CLI(`gh`)가 필요하다.** 명령이 `gh pr view`, `gh pr diff`, `gh pr comment` 등을 사용한다. `gh`가 없거나 로그인되어 있지 않으면 동작하지 않는다.
3. 이미 닫힌 PR, 초안(draft) PR, 아주 단순한 PR, **이미 이 리뷰를 받은 PR은 건너뛴다.** 높은 신뢰도의 문제가 없으면 댓글도 달지 않는다.
4. 여러 에이전트를 병렬로 돌리므로 한 번에 토큰이 꽤 든다.

## 언제 쓰나

- 올라온 PR을 병합 전에 한 번 더 점검하고 싶을 때
- 내가 올린 PR에서 사람 리뷰 전에 명백한 문제를 걸러내고 싶을 때

게시 없이 변경 내용만 점검하고 싶으면, 이 명령 대신 현재 변경분을 직접 검토하는 쪽이 맞다 (결과를 사용자에게 보고만 하고 PR에는 쓰지 않는다).

## 1. 설치 여부 확인

```bash
claude plugin list        # code-review@claude-plugins-official 가 보이는지
gh --version              # 필요: GitHub CLI
gh auth status            # 로그인 상태 (토큰 값은 출력·기록하지 않는다)
```

## 2. 설치

```bash
claude plugin marketplace add anthropics/claude-plugins-official     # 공식 마켓이 아직 없을 때만
claude plugin install code-review@claude-plugins-official
```

Claude Code 대화창에서는 `/plugin install code-review@claude-plugins-official`. 프로젝트에만 쓰려면 `--scope local`.
설치 후 Claude Code를 완전히 종료했다가 다시 켠다.

## 3. 사용

리뷰할 PR 브랜치에서 (또는 대상 PR을 알려 주고):

```text
/code-review
```

흐름: 리뷰가 필요한 PR인지 확인 → 관련 CLAUDE.md 수집 → 변경 요약 → 여러 에이전트가 병렬로 검토 → 문제별 신뢰도 점수 → 80점 이상만 남김 → PR에 댓글 게시.

## 4. 결과 보고

- 어느 PR(번호, 저장소)을 리뷰했는지
- 건너뛰었다면 이유 (닫힘, 초안, 이미 리뷰됨, 단순 PR 등)
- 게시된 댓글의 요지와 링크. 문제가 없어 게시하지 않았다면 그렇게 적는다.

## 지켜야 할 것

- **사용자 확인 없이 PR에 댓글을 게시하지 않는다.** 한 번의 허락은 다른 PR로 이어지지 않는다.
- 저장소 접근 권한이 없는 PR이나 사용자가 지정하지 않은 저장소를 리뷰하지 않는다.
- 리뷰 댓글에 비밀값(토큰, 키, 개인정보)을 인용하지 않는다.
- 리뷰 결과의 신뢰도 점수와 근거를 사실대로 전한다. 점수가 낮아 버려진 항목을 임의로 되살리지 않는다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| `gh: command not found` | GitHub CLI 설치 후 `gh auth login` |
| 아무 댓글도 안 달림 | 의도된 동작일 수 있음 (높은 신뢰도의 문제 없음, 또는 PR이 초안·닫힘·이미 리뷰됨) |
| 설치했는데 `/code-review`가 안 보임 | Claude Code 완전 종료 후 재시작 |

공식 자료: <https://github.com/anthropics/claude-plugins-official> (플러그인 소개: `plugins/code-review`)
