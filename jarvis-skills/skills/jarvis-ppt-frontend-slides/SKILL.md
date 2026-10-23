---
name: jarvis-ppt-frontend-slides
description: "Use when a talk, pitch or launch needs a distinctive animated web presentation (single HTML file, arrow-key navigation) instead of a default template, or when a .pptx must be converted to the web. Installs and drives the frontend-slides plugin. 광고 포스터 느낌 발표, 화풍을 먼저 고르는 웹 발표(.html), PPT를 웹으로 변환."
---

# frontend-slides: 화풍부터 고르는 웹 발표

"어디서 본 듯한 기본 틀"을 막아 주는 도구입니다. 만들기 전에 **화풍 후보를 먼저 보여 주고**, 고른 느낌대로 브라우저에서 ← → 로 넘기는 발표 파일(.html)을 만듭니다.
디자인을 잘 모르는 사람도 눈으로 보고 고를 수 있습니다. 결과는 외부 의존성이 없는 단일 HTML 파일입니다.

## 언제 쓰나 / 쓰지 말아야 할 때

- 신메뉴, 이벤트, 행사 소개처럼 **광고 포스터 느낌**의 발표
- 기존 PowerPoint 파일을 웹 발표로 변환할 때
- 쓰지 말 것: 결과를 **파워포인트에서 고쳐야 한다**면 .pptx를 만드는 `jarvis-ppt-document-skills` / `jarvis-ppt-master`를 쓴다. 이 도구의 결과는 .html이다.

## 1. 설치 여부 확인

```bash
claude plugin list      # frontend-slides@frontend-slides 가 보이는지
```

## 2. 설치

```bash
claude plugin marketplace add https://github.com/zarazhangrui/frontend-slides
claude plugin install frontend-slides@frontend-slides
```

- **HTTPS 전체 주소로 적는다.** 짧은 `zarazhangrui/frontend-slides` 형태는 SSH로 시도하다 실패할 수 있다고 공식 문서가 안내한다.
- **주소를 꺾쇠(`<` `>`)로 감싸지 않는다.** 문서에서 복사할 때 `<https://…>` 형태로 붙는 경우가 있는데, 꺾쇠가 들어가면 안 된다.
- Claude Code 대화창에서 쓸 때는 `/plugin marketplace add https://github.com/zarazhangrui/frontend-slides`와 `/plugin install frontend-slides@frontend-slides`를 **두 개의 메시지로 따로** 입력한다. 한 번에 붙여넣지 않는다.
- 설치 후 Claude Code를 완전히 종료했다가 다시 켠다.

## 3. 사용

플러그인으로 설치한 스킬은 `/플러그인이름:스킬이름` 형태로 호출한다.

```text
/frontend-slides:frontend-slides [수제버거 신메뉴 «핫치킨 버거»] 출시 발표 6장. 광고 포스터 느낌으로
```

흐름: 화풍 후보가 먼저 뜬다 → 사용자가 고른다 → 브라우저에서 ← → 로 넘기는 `.html` 파일이 나온다.
(스킬을 수동으로 `~/.claude/skills/`에 복사해 설치했다면 `/frontend-slides`로 호출한다. 이름 앞 접두어가 없다.)

- PDF로 내보내기는 지원되지만 각 장을 캡처한 정지 이미지라 **애니메이션은 유지되지 않는다.** PDF 내보내기에는 Node.js가 필요하다.

## 결과 확인과 보고

- 생성된 `.html`의 경로를 알리고, 가능하면 `jarvis-agent-browser`로 열어 장수, 넘김, 레이아웃을 확인한다. 스크린샷 경로를 함께 적는다.
- 브라우저로 확인하지 못했으면 확인하지 못했다고 적는다.

## 지켜야 할 것

- **화풍은 사용자가 고른다.** 임의로 정하지 않고 후보를 보여 준다.
- 행사 일정, 가격, 메뉴 이름 같은 사실은 사용자가 준 것만 쓴다. 지어내지 않는다.
- 기존 파일을 덮어쓰지 않는다. PowerPoint 변환 시 원본 .pptx는 수정하지 않는다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| 설치 중 SSH 오류 | HTTPS 전체 주소로 다시 시도 |
| 마켓 등록이 실패함 | 주소에 `<` `>`가 붙지 않았는지, HTTPS 전체 주소인지 확인 |
| 설치했는데 안 보임 | Claude Code 완전 종료 후 재시작 |

공식 자료: <https://github.com/zarazhangrui/frontend-slides>
