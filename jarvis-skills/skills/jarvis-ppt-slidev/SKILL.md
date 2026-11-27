---
name: jarvis-ppt-slidev
description: "Use when the user prefers to write slides as markdown and present them from the browser: scaffold a Slidev project (npm init slidev), edit slides.md, run the dev server. 글로 적어서 웹에서 넘기며 발표, 마크다운 발표 자료, Slidev."
---

# Slidev: 글을 쓰면 넘기는 발표가 된다

마크다운 글(`slides.md`)만 적으면 사진과 애니메이션이 들어간 발표 화면으로 바뀝니다.
날짜와 장소만 나열한 밋밋한 장표를 막아 주고, 클로드가 글만 써 줘도 바로 발표가 됩니다. 글을 고치면 브라우저 화면이 바로 바뀝니다.

## 언제 쓰나 / 쓰지 말아야 할 때

- 글로 적는 것이 편한 사람, 클로드가 글만 써 주면 바로 발표가 되길 원할 때
- 쓰지 말 것: 결과를 파워포인트 파일로 넘겨야 한다면 `jarvis-ppt-document-skills` / `jarvis-ppt-master`를 쓴다.

## 1. 준비 확인

```bash
node --version      # v22.12.0 이상이어야 한다 (Slidev 요구 사항)
```

Node.js가 없거나 낮으면 <https://nodejs.org>에서 LTS를 설치하고 터미널을 새로 연다.

## 2. 새 발표 만들기

```bash
mkdir my-talk && cd my-talk     # 빈 폴더에서 시작한다
npm init slidev
```

- `npm init slidev`는 질문을 하며 진행하는 **대화형 도구**다. 프로젝트 이름, 의존성 설치, 개발 서버 시작 여부를 묻는다. 사용자 입력을 받을 수 없는 환경이면 사용자에게 직접 실행해 달라고 요청한다.
- 기존 프로젝트 폴더 안에서 실행하지 않는다. 항상 새 폴더에서 만든다.

## 3. 사용

생성된 폴더를 Claude Code에서 열고 요청한다.

```text
slides.md 를 [제주 2박 3일 여행 코스] 발표로 바꿔줘. 장마다 사진 배경, 코스는 번호가 하나씩 뜨게
```

성공하면 브라우저에 발표 화면이 열리고, 글을 고치면 바로 바뀐다. 개발 서버 주소는 시작할 때 출력되는 것을 쓴다.

## 결과 확인과 보고

- 개발 서버가 실제로 떠 있는지 확인한다 (출력된 주소로 접속).
- 가능하면 `jarvis-agent-browser`로 발표 화면을 열어 장 넘김과 레이아웃을 확인하고 스크린샷 경로를 적는다.
- 확인하지 못했으면 확인하지 못했다고 적는다.

## 지켜야 할 것

- 사진은 출처와 사용 가능 여부를 확인하고 함께 알린다.
- 여행 코스, 일정, 가격 같은 사실은 사용자가 준 것만 쓴다. 지어내지 않는다.
- 개발 서버는 확인이 끝나면 종료하고, 종료했다고 알린다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| `npm`·`npx`를 못 찾음 | Node.js 설치 후 터미널 새로 열기 (Windows 포함) |
| 엔진 버전 경고·실패 | Node를 22.12.0 이상으로 올리기 |

공식 자료: <https://github.com/slidevjs/slidev> · <https://sli.dev>
