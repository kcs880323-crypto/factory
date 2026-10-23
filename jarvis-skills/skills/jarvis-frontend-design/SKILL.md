---
name: jarvis-frontend-design
description: "Use when building or reshaping a UI (a component, a page, an app screen) and it should have a deliberate visual identity instead of templated AI defaults. Installs and uses Anthropic's official frontend-design plugin from claude-plugins-official. 새 화면·컴포넌트를 만들 때 뻔한 AI 디자인을 피하고 싶을 때, 가볍게 쓰는 디자인 기준."
---

# frontend-design: 뻔한 디자인을 피하는 가벼운 디자인 기준

Anthropic이 공식 마켓(`claude-plugins-official`)에 올린 디자인 스킬입니다. 새 화면을 만들거나 기존 화면을 고칠 때 **미적 방향, 글꼴, 템플릿 같지 않은 선택**을 잡아 줍니다.
스크립트나 브라우저 검사 도구가 없는 **텍스트 지침형 스킬**이라 가볍게 쓸 수 있습니다.

## 언제 쓰나 / `jarvis-design-taste`와의 차이

| | frontend-design (이 스킬) | `jarvis-design-taste` (Taste) |
|---|---|---|
| 성격 | 디자인 지침 (가벼움) | 스크립트·실제 브라우저 점검·사진 수집까지 하는 긴 작업 |
| 맞는 경우 | 컴포넌트, 작은 화면, 빠른 수정, 기존 화면 다듬기 | 랜딩 페이지·사이트 한 장을 통째로 새로 디자인 |
| 시간·비용 | 보통 작업 수준 | 페이지당 30~45분, 수십만 토큰 |

**둘을 한 작업에 같이 쓰지 않는다.** 크기에 맞는 하나를 고르고, 어느 쪽을 골랐는지 사용자에게 알린다. 어느 쪽이든 완성 후에는 `jarvis-agent-browser`로 실제 화면을 확인한다.

## 1. 설치 여부 확인

```bash
claude plugin list        # frontend-design@claude-plugins-official 가 보이는지
```

## 2. 설치

```bash
claude plugin marketplace add anthropics/claude-plugins-official     # 공식 마켓이 아직 없을 때만
claude plugin install frontend-design@claude-plugins-official
```

Claude Code 대화창에서는 `/plugin install frontend-design@claude-plugins-official`을 입력한다 (원격·자동화 환경에서는 슬래시 명령을 못 쓰는 경우가 있어 위 터미널 명령이 같은 일을 한다).
프로젝트에만 설치하려면 `--scope local`, 설치 후에는 Claude Code를 완전히 종료했다가 다시 켠다.

## 3. 사용

만들 화면과 함께 **무엇을 위한 화면인지(대상, 핵심 역할)** 를 알려 준다. 스킬은 주제가 불분명하면 먼저 주제·대상·핵심 역할을 제안하고 확인을 받도록 되어 있다.

```text
<만들 화면 설명>을 만들어 줘. 대상은 <누구>, 이 화면의 핵심 역할은 <무엇>.
frontend-design 스킬의 기준을 따라 줘. 흔한 템플릿 같은 느낌은 피하고.
```

스킬의 작업 흐름: 주제에 근거한 디자인 방향 정하기 → 계획 → 브리프에 맞는지 검토 → 구현 → 스스로 비평. 절제와 자기 점검을 강조한다.

## 지켜야 할 것

- 사용자가 화면의 주제·대상을 주지 않았으면 임의로 정하지 말고 제안해서 확인받는다.
- 문구, 가격, 상호, 통계 같은 내용은 사용자가 준 것만 쓴다. 지어내지 않는다.
- 결과 보고에는 실제로 화면을 열어 확인했는지(스크린샷 경로)를 적는다. 확인하지 못했으면 못 했다고 쓴다.
- 설치된 플러그인 파일은 수정하지 않는다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| `Plugin "frontend-design" not found in marketplace` | 공식 마켓이 내려받아지지 않은 것. `claude plugin marketplace add anthropics/claude-plugins-official` 후 다시 설치 |
| 설치했는데 반영 안 됨 | Claude Code 완전 종료 후 재시작 |

공식 자료: <https://github.com/anthropics/claude-plugins-official> (플러그인 소개: `plugins/frontend-design`)
