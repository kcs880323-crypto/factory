---
name: jarvis-design-taste
description: "Use when building or redesigning a website, landing page, dashboard or app screen and the result must not look like the generic AI default. Installs and drives Taste Skill (design-taste-frontend) and checks the result in a real browser. 웹 화면·랜딩페이지·대시보드 디자인, 뻔한 AI 디자인 피하기."
---

# Taste: 디자인 기본값을 바꿔 주는 스킬

기준 없이 화면을 만들게 하면 AI는 늘 보라·파랑 그라데이션에 카드 안의 카드 같은 비슷한 화면으로 돌아갑니다.
Taste(`design-taste-frontend`)는 그 기준을 대신 세워 줍니다: 아트 디렉션을 정하고, 실제 사진을 찾아 출처를 달고, 만든 화면을 실제 브라우저에서 스스로 점검합니다.

## 언제 쓰나

- 웹 사이트, 랜딩 페이지, 대시보드, 관리자 화면, 앱 화면을 새로 만들 때
- 이미 있는 페이지를 디자인 관점에서 개선할 때 (이 스킬이 변경 전에 실제 브라우저로 현재 상태를 점검한다)
- React, Vue, Svelte, 순수 HTML 모두 가능하다. 규칙은 프레임워크가 아니라 디자인에 대한 것이다.

## 1. 준비 확인

```bash
node --version                    # 22 이상
ls .claude/skills ~/.claude/skills   # design-taste-frontend 가 이미 있는지
```

필요한 환경: Node 22+, 설치된 Chrome / Edge / Chromium. API 키는 필요 없다. 사진을 찾을 때만 인터넷이 필요하다.

## 2. 설치

```bash
npx --yes skills@latest add https://github.com/Leonxlnx/taste-skill --skill design-taste-frontend --agent claude-code --yes --copy
```

- 성공하면 `✓ design-taste-frontend (copied)` 줄이 나온다.
- **`--skill design-taste-frontend`를 꼭 붙인다.** 옵션 없이 저장소를 통째로 설치하면 비슷한 스킬이 여러 개 한꺼번에 들어올 수 있다. 이미 설치돼 있으면 같은 명령을 다시 실행해도 된다(설치 이름은 바뀌지 않는다).
- 이 저장소의 다른 스킬(`high-end-visual-design`, `minimalist-ui` 등)은 `Leonxlnx/taste-skill-classics`로 옮겨졌다. 필요하면 그 저장소에서 같은 이름으로 설치한다.

### 플러그인으로 설치하는 방법 (공식 안내의 두 번째 방법)

같은 스킬을 Claude Code 플러그인으로도 받을 수 있다. 격리된 HOME에서 실제로 설치해 v2.1.5가 들어오고 스킬 이름이 `design-taste-frontend`인 것을 확인했다.

```bash
claude plugin marketplace add "Leonxlnx/taste-skill#v2.1.5" --scope user
claude plugin install taste-skill@taste-skill --scope user
```

**위의 `npx skills` 설치와 둘 중 하나만 쓴다** (둘 다 하면 같은 지침이 중복된다). 이미 `design-taste-frontend`가 있으면 이 방법을 쓰지 않는다. 업데이트는 `claude plugin update taste-skill@taste-skill`.

### 다른 디자인 스킬과 같이 쓰지 않는다

디자인 방향을 정하는 스킬(Frontend Design = `jarvis-frontend-design`, UI UX Pro Max = `jarvis-ui-ux-pro-max`, 이 Taste)은 **한 작업에 하나만** 쓴다. 랜딩 페이지 한 장을 통째로(사진, 브라우저 점검 포함) 만들 때가 Taste, 디자인 규칙(색·글꼴·섹션 순서)부터 정할 때는 UI UX Pro Max, 작은 화면은 Frontend Design이다.

## 3. 쓰는 방법

만들 화면 설명과 함께 **하지 말아야 할 것**을 명시하면 결과가 달라진다.

```text
<만들 화면 설명>을 만들어 주세요.
디자인 스킬의 기준을 따르고, 아래는 쓰지 마세요.
- 보라나 파랑 계열 그라데이션 배경
- 카드 안에 카드를 넣는 구조
- 튀기는 바운스 효과
```

스킬이 진행하는 순서(요약):

1. 브리프를 읽고 이 의뢰인만 가진 소재를 정리한다.
2. 연구된 20개 방향 중 스크립트가 3개를 뽑고, 하나를 고른다.
3. 의뢰인의 소재와 선택한 방향을 잇는 한 문장의 아이디어를 정한다.
4. 공개 자료실의 실제 사진·영상으로 만든다 (출처 표기).
5. 실제 브라우저에서 세 가지 너비로 점검한다.
6. 자기 스크린샷을 보고 문제 3개를 고친다 (최대 3회).

대시보드·앱 화면은 별도 경로로 진행되며, 정렬된 숫자·조용한 패널·모바일에서 접히는 내비게이션 같은 앱용 규칙으로 점검한다.

## 시작하기 전에 사용자에게 알릴 것

- **작업 시간과 비용이 크다.** 페이지 하나에 대략 30~45분, 수십만 토큰이 든다고 스킬 제작자가 밝히고 있다. 시작 전에 사용자에게 말하고 진행한다.
- 사진은 Wikimedia Commons, Openverse 등에서 공개 라이선스(퍼블릭 도메인, CC0, CC BY, CC BY-SA)인 것만 쓰고 출처를 페이지에 싣는다. 사용자에게 자기 사진이 있으면 교체하도록 안내한다.

## 함께 쓰면 좋은 것

- `jarvis-agent-browser`: 완성 후 실제 클릭·링크·폼 동작까지 눌러서 확인
- `jarvis-gsd`: 화면이 여러 개인 큰 작업을 단계로 나눠 진행

## 지켜야 할 것

- 설치된 스킬 원본 파일을 임의로 수정하지 않는다.
- 사진 출처 표기를 임의로 지우지 않는다.
- 결과를 보고할 때는 실제 브라우저에서 확인한 너비와 스크린샷 경로를 함께 적는다. 확인하지 않았으면 확인하지 않았다고 쓴다.

공식 자료: <https://tasteskill.dev> · <https://github.com/Leonxlnx/taste-skill>
