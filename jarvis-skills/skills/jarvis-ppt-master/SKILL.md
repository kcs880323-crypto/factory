---
name: jarvis-ppt-master
description: "Use when a report, document or topic must become a data-rich deck with charts, tables, shapes and transitions as editable .pptx, or when an existing .pptx must be rebuilt, beautified or filled from a template. Installs and runs PPT Master (hugohe3/ppt-master). 보고서·문서를 표·그래프가 들어간 장표로 바꿀 때."
---

# PPT Master: 문서를 그래프·표가 있는 장표로

숫자만 줄줄이 적힌 장표를 막아 주는 도구입니다. 문서나 주제를 주면 도형, 전환 효과, 데이터 그래프, 표가 들어간 파워포인트(.pptx)로 바꿔 줍니다. 내 .pptx 양식도 쓸 수 있습니다. 오픈소스(MIT)이고 기본 사용에는 API 키가 필요 없습니다.

## 언제 쓰나

- 보고서·문서·자료를 장표 여러 장으로 바꿀 때 (분기별 수치를 막대그래프로 등)
- 기존 .pptx를 다시 만들거나 보기 좋게 고칠 때, 템플릿에 내용을 채울 때

단순한 소개 PPT를 빠르게 만들고 싶으면 `jarvis-ppt-document-skills`가 더 가볍다.

## 1. 준비 확인

```bash
python3 --version          # 3.10 이상이어야 한다
claude plugin list         # ppt-master@ppt-master 가 보이는지
```

## 2. 설치 (2단계, 둘 다 필요하다)

**(a) 플러그인 설치** — 스킬 파일만 받아진다.

```bash
claude plugin marketplace add hugohe3/ppt-master
claude plugin install ppt-master@ppt-master
```

Claude Code 안에서는 `/plugin marketplace add hugohe3/ppt-master`, `/plugin install ppt-master@ppt-master`를 한 줄씩 입력한다.

**(b) 파이썬 부품 설치 — 빼먹으면 작업이 마지막에 멈춘다.** 설치된 폴더의 `requirements.txt`로 설치한다.

```bash
REQ=$(ls -t "${CLAUDE_CONFIG_DIR:-$HOME/.claude}"/plugins/cache/ppt-master/ppt-master/*/ppt-master/requirements.txt | head -1)
python3 -m pip install -r "$REQ"
```

- 설치 경로에는 버전 해시가 들어 있어 업데이트하면 바뀐다. 위처럼 찾아서 쓴다.
- 시스템 파이썬과 충돌하면(`externally-managed-environment`, 또는 `Cannot uninstall … The package was installed by debian`) 가상환경을 만들어 그 안의 `python -m pip`로 설치한다. 실제로 데비안 계열에서 이 오류를 확인했다. 가상환경에 설치했다면 PPT Master 스크립트도 **그 가상환경의 파이썬으로 실행해야** 부품을 찾는다. 사용자에게 어느 가상환경에 설치했는지 알린다.
- 설치 후 Claude Code를 완전히 종료했다가 다시 켠다.

## 3. 사용

```text
이 문서로 [요가원 2026 연간 보고] 장표 10장 만들어줘. 분기별 회원 수는 막대그래프로
```

성공하면 그래프·표가 들어간 .pptx가 돌아온다. 자료(문서 파일 경로)를 함께 주면 그것을 바탕으로 만든다.

### 이미지 (선택)

- 웹 이미지 검색은 별도 설정 없이 동작한다.
- 더 좋은 검색 결과를 원하면 무료 키(`PEXELS_API_KEY`, `PIXABAY_API_KEY`)를 설정할 수 있다. AI 이미지 생성은 클로드 환경의 이미지 도구를 쓰거나 공급자 API 키가 필요하다. 이 키들은 `.env`에 저장된다.
- **`.env` 파일은 읽거나 출력하지 않는다.** 키가 필요하면 사용자에게 설정을 요청한다.

### 라이선스 참고

PDF를 원본 자료로 변환하는 기능은 PyMuPDF(AGPL-3.0)에 의존한다. PDF 원본이 있을 때만 필요하며, 상업적 사용이면 사용자에게 알린다.

## 결과 확인과 보고

- 생성된 .pptx의 경로와 장수를 알린다.
- 그래프 수치가 원본 자료와 일치하는지 확인한다. 확인하지 못했으면 그렇게 적는다.

## 지켜야 할 것

- **수치는 사용자가 준 자료에서만 가져온다.** 없는 값을 만들어 그래프에 넣지 않는다.
- 사용자의 원본 문서와 양식 .pptx는 수정하지 않고 새 파일로 저장한다.
- 같은 이름의 파일을 덮어쓰지 않는다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| 작업이 마지막에 멈춤 | 2-(b)의 `pip install -r`을 했는지, 가상환경이면 그 파이썬으로 실행했는지 확인 |
| `python3` 버전이 낮음 | 3.10 이상 설치 |
| 설치했는데 안 보임 | Claude Code 완전 종료 후 재시작 |

공식 자료: <https://github.com/hugohe3/ppt-master>
