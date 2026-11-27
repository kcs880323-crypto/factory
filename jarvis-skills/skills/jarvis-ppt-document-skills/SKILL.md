---
name: jarvis-ppt-document-skills
description: "Use when the user wants a real, editable .pptx file (or .docx/.xlsx/.pdf) made from scratch, e.g. 'make a 5-slide intro deck as a pptx'. Installs and uses Anthropic's official document-skills plugin. 처음 PPT를 만들 때, 파워포인트로 열어 바로 고칠 수 있는 진짜 .pptx 파일이 필요할 때."
---

# document-skills: 진짜 .pptx 파일 받기

클로드에게 PPT를 부탁하면 "파일은 못 만들고 내용만 써 드릴게요"로 끝나는 경우가 많습니다.
Anthropic이 직접 낸 공식 스킬 `document-skills`를 설치하면 클로드가 파워포인트(.pptx), 워드, 엑셀, PDF 파일을 직접 만듭니다.
PPT 도구 중 **가장 먼저 시작하기 좋은 것**입니다. 결과가 진짜 .pptx라서 파워포인트에서 열어 글자와 사진을 그대로 고칠 수 있습니다.

## 언제 쓰나 / 다른 도구가 나은 경우

| 상황 | 도구 |
|---|---|
| 처음이다, 소개·안내용 PPT를 .pptx로 받고 싶다 | **이 스킬** |
| 긴 보고서·문서를 표·그래프 장표로 바꾸고 싶다 | `jarvis-ppt-master` |
| 광고 포스터 같은 웹 발표(.html)를 원한다 | `jarvis-ppt-frontend-slides` |
| 글로 적어서 웹에서 넘기며 발표하고 싶다 | `jarvis-ppt-slidev` |
| 회사 양식 .pptx가 이미 있고 내용만 바꾸고 싶다 | `jarvis-ppt-powerpoint-mcp` |

## 1. 설치 여부 확인

```bash
claude plugin list        # document-skills@anthropic-agent-skills 가 보이면 설치된 것
```

이미 있으면 다시 설치하지 않는다.

## 2. 설치

터미널에서:

```bash
claude plugin marketplace add anthropics/skills
claude plugin install document-skills@anthropic-agent-skills
```

Claude Code 대화창 안에서 입력하려면 같은 내용을 슬래시 명령으로 쓴다 (한 줄씩 따로 입력).

```text
/plugin marketplace add anthropics/skills
/plugin install document-skills@anthropic-agent-skills
```

- 프로젝트에만 쓰려면 `--scope local`, 계정 전체는 기본값(`--scope user`)이다.
- 이 플러그인에는 xlsx, docx, **pptx**, pdf 스킬 4개가 함께 들어 있다.
- 설치 후 Claude Code를 완전히 종료했다가 다시 켠다. 재시작 전에는 인식하지 못할 수 있다.
- 플러그인으로 설치된 스킬을 직접 호출할 때는 `/플러그인이름:스킬이름` 형태를 쓴다. 보통은 요청만 하면 클로드가 알아서 고른다.

## 3. 사용

요청에 **장수, 표지 느낌, 들어갈 요소, 저장 형식**을 적는다.

```text
[가평 글램핑장] 소개 PPT 5장 만들어줘. 표지는 사진 꽉 차게,
객실 수·가격·별점은 카드로. pptx 파일로 저장해줘
```

성공하면 작업 폴더에 `.pptx` 파일이 생기고, 파워포인트로 열어 그대로 고칠 수 있다.

## 결과 확인과 보고

- `ls *.pptx`로 파일이 실제로 생겼는지 확인하고 경로를 알린다.
- 요청한 장수, 제목, 들어간 요소(카드, 그래프 등)가 맞는지 확인한다.
- 확인하지 못한 항목(예: 파워포인트에서 열어 보지 못함)은 "확인하지 못했다"고 적는다.

## 지켜야 할 것

- **수치와 사실은 사용자가 준 자료만 쓴다.** 가격, 별점, 객실 수, 매출 같은 숫자를 지어내지 않는다. 자료가 없으면 자리표시자(예: `[가격 입력]`)로 두고 어떤 항목이 비었는지 알린다.
- 같은 이름의 파일이 이미 있으면 덮어쓰지 않고 새 이름으로 저장한다.
- 사진을 쓰면 출처와 사용 가능 여부를 함께 알린다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| 설치했는데 안 보임 | Claude Code를 완전히 종료 후 재시작 |
| `/plugin` 명령이 안 됨 | Claude Code 최신 버전으로 업데이트 (`claude update`) |
| 명령이 안 먹힘 | 저장소 이름의 대소문자까지 그대로 복사 |

공식 자료: <https://github.com/anthropics/skills>
