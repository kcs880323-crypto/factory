---
name: jarvis-theme-factory
description: "Use when an existing artifact (slide deck, document, report, HTML landing page) needs consistent colors and fonts: show the preset theme showcase, let the user pick one of 10 themes (or generate a custom one), then apply it. Installs Anthropic's theme-factory skill. 만든 장표·문서·페이지에 색·글꼴 테마를 일관되게 입힐 때."
---

# theme-factory: 완성된 결과물에 색·글꼴 테마 입히기

Anthropic의 `theme-factory`는 슬라이드, 문서, 보고서, HTML 랜딩 페이지 같은 **이미 만든 결과물**에 **색 팔레트와 글꼴 조합(테마)** 을 일관되게 입혀 주는 스킬입니다. 미리 만든 테마 10개가 있고, 맞는 게 없으면 새 테마를 만들어 줍니다.

## 먼저 알아 둘 것

1. **테마를 고르는 사람은 사용자다.** 스킬은 `theme-showcase.pdf`를 **수정 없이 그대로 보여 주고 → 어떤 테마를 쓸지 묻고 → 사용자가 명시적으로 확인한 뒤에** 적용하도록 되어 있다. 자비스가 임의로 테마를 고르지 않는다.
2. **프리셋 테마는 템플릿에 가깝다.** 색과 글꼴을 빠르게 통일하는 도구이지, 그 주제만의 고유한 디자인 정체성을 만드는 도구가 아니다. 그래서 **`jarvis-frontend-design`이나 `jarvis-design-taste`(Taste)와 같은 결과물에 같이 쓰지 않는다.** 새 화면의 디자인 방향을 잡는 일은 그쪽이, 이미 있는 결과물의 색·글꼴을 통일하는 일은 이쪽이 맡는다.
3. **한글이 안 나오는 글꼴이 지정되어 있다.** 테마 파일의 글꼴은 `DejaVu Sans`/`DejaVu Serif`, `FreeSans`/`FreeSerif` 계열이다. 이 환경에서 fontconfig로 확인한 결과 이 글꼴들은 한글 지원 글꼴 목록에 없었다. 또 이 글꼴들은 윈도우·맥에는 보통 기본 설치되어 있지 않다. **한글 문서에는 팔레트(색)만 쓰고, 글꼴은 사용자에게 한글 글꼴을 확인해서 정한다** (예: 문서에 이미 쓰고 있는 글꼴). 지정된 글꼴로 한글을 표시했다고 보고하지 않는다.
4. 이 스킬은 **스타일만 바꾼다.** 내용(문구, 수치, 구성)은 바꾸지 않는다.

## 테마 10개 (스킬 본문 기준)

Ocean Depths(차분한 해양), Sunset Boulevard(따뜻한 노을), Forest Canopy(자연스러운 흙빛), Modern Minimalist(깔끔한 그레이스케일), Golden Hour(따뜻한 가을 색), Arctic Frost(차갑고 선명한 겨울), Desert Rose(부드러운 먼지 톤), Tech Innovation(대담한 테크), Botanical Garden(싱그러운 정원), Midnight Galaxy(극적인 짙은 우주 색).

각 테마는 `themes/` 폴더의 파일에 색 팔레트(16진수 색 코드), 헤더·본문 글꼴, 어울리는 용도가 정리되어 있다.

## 1. 설치 여부 확인

```bash
ls .claude/skills/theme-factory ~/.claude/skills/theme-factory 2>/dev/null
```

## 2. 설치

```bash
npx --yes skills@latest add anthropics/skills --skill theme-factory --agent claude-code --yes --copy
```

- **`--skill theme-factory`를 꼭 붙인다.** `anthropics/skills`는 여러 스킬이 든 저장소라 옵션 없이 설치하면 전부 들어온다.
- 설치하면 `SKILL.md`, `LICENSE.txt`, `theme-showcase.pdf`, `themes/` 폴더(테마 파일 10개)가 생기는 것을 확인했다.
- 공식 마켓 플러그인 `example-skills@anthropic-agent-skills`로도 설치되지만, 이 플러그인은 스킬 12개를 한꺼번에 넣는다. 필요한 것만 쓰려면 위 명령이 낫다.
- 이 패키지의 스크립트로는 `install.sh --with-design-skills`.
- 설치 후 Claude Code를 완전히 종료했다가 다시 켠다.

## 3. 사용 순서

대상 파일(장표, 문서, HTML)이 이미 있어야 한다.

```text
<파일>에 theme-factory 로 테마를 입혀 줘. 테마 미리보기부터 보여 주고 내가 고를게.
```

1. `theme-showcase.pdf`를 **수정 없이** 보여 준다.
2. 사용자에게 어떤 테마를 쓸지 묻는다.
3. **사용자가 명시적으로 고를 때까지 기다린다.**
4. 선택된 테마 파일(`themes/<이름>.md`)을 읽고, 색과 글꼴을 결과물 전체에 일관되게 적용한다. 글자 대비와 가독성을 확인한다.

맞는 테마가 없으면: 사용자가 준 설명을 바탕으로 **새 테마를 만들고(기존 테마와 비슷한 형식, 설명이 담긴 이름)**, 적용 전에 사용자에게 **보여 주고 확인**받은 뒤 같은 방식으로 적용한다.

## 4. 결과 확인과 보고

- 적용한 테마 이름과 사용한 색·글꼴을 적는다.
- **원본 파일은 수정하지 않고 새 이름으로 저장**하고 경로를 알린다.
- 글자 대비를 확인했는지, 한글 글꼴을 어떻게 처리했는지 적는다.
- 결과 파일을 실제로 열어 보지 못했으면 "확인하지 못했다"고 쓴다 (웹 결과물은 `jarvis-agent-browser`로 확인할 수 있다).

## 함께 쓰기

- 장표: `jarvis-ppt-document-skills`(.pptx), `jarvis-ppt-master`(.pptx), `jarvis-ppt-frontend-slides`(.html)로 내용을 만든 뒤 이 스킬로 통일된 색·글꼴을 입힌다.
- 웹 결과물: 완성 후 `jarvis-agent-browser`로 실제 화면을 확인한다.

## 지켜야 할 것

- 테마는 사용자가 고른다. 임의로 고르거나 "추천대로 했다"며 진행하지 않는다.
- `theme-showcase.pdf`와 설치된 테마 파일을 수정하지 않는다.
- 내용은 바꾸지 않는다. 같은 이름의 파일을 덮어쓰지 않는다.
- 새 테마를 만들 때는 먼저 보여 주고 확인받는다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| 한글이 네모(□)로 보이거나 다른 글꼴로 바뀜 | 테마 글꼴이 한글을 지원하지 않기 때문. 한글 글꼴을 사용자와 정해 글꼴만 교체 |
| 지정 글꼴이 PC에 없다고 대체됨 | 글꼴 설치 또는 대체 글꼴을 사용자와 정함 |
| 설치했는데 반영 안 됨 | Claude Code 완전 종료 후 재시작 |

공식 자료: <https://github.com/anthropics/skills> (`skills/theme-factory`)
