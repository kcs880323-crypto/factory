---
name: jarvis-agent-browser
description: "Use when a web/UI change has to be checked in a real browser before you report it done: open the page, click, fill forms, take screenshots with Vercel's agent-browser CLI. Also covers installing and verifying agent-browser. 브라우저로 화면 직접 확인, 스크린샷, 폼 입력·링크·버튼 동작 검증."
---

# agent-browser: 만든 화면을 직접 열어서 확인하기

Claude는 기본적으로 코드만 쓰고 결과 화면을 보지 못한 채 "다 됐다"고 보고합니다.
agent-browser(Vercel 제작)는 크롬을 열고, 클릭하고, 입력하고, 화면을 캡처하는 일을 Claude가 직접 하게 해 주는 명령줄 도구입니다.
이 스킬은 **설치 확인 → 화면 확인 → 결과 보고**까지의 절차를 정리한 것입니다.

> 이 스킬은 안내문입니다. 실제로 브라우저를 움직이는 건 `agent-browser` 명령줄 도구이므로 아래 2단계에서 따로 설치해야 합니다.

## 언제 쓰나

- 웹 화면(페이지, 폼, 대시보드)을 만들거나 고친 뒤 "됐다"고 보고하기 전
- 버튼이 눌리는지, 링크가 실제로 이동하는지, 폼이 제출되는지 확인할 때
- 화면이 깨졌다는 말을 듣고 원인을 눈으로 확인할 때

## 1. 준비 확인 (이미 있으면 다시 설치하지 않는다)

```bash
node --version            # v22.20 이상이어야 한다
agent-browser --version   # 버전 숫자가 나오면 준비 끝
```

둘 다 정상이면 3단계로 넘어간다.

## 2. 설치 (없는 것만)

```bash
# (a) 스킬 안내문 — 프로젝트 폴더에 설치. 계정 전체에 쓰려면 -g 추가
npx --yes skills@latest add vercel-labs/agent-browser --agent claude-code --yes --copy

# (b) 실제 도구 + 자동화 전용 크롬 (둘 다 필요)
npm install -g agent-browser
agent-browser install

# (c) 리눅스에서 브라우저가 안 뜨면 시스템 파일 부족
agent-browser install --with-deps
```

자동화 전용 크롬은 평소 쓰는 크롬과 따로 설치되어 로그인 상태나 북마크에 영향을 주지 않는다.
설치 후 `agent-browser --version`으로 확인한다.

## 3. 기본 작업 흐름

설치된 원본 `agent-browser` 스킬은 사용법 전체가 아니라 입구 역할만 한다. 명령을 쓰기 전에 설치된 버전에 맞는 최신 사용법을 CLI에서 읽는다.

```bash
agent-browser skills get core          # 작업 흐름, 자주 쓰는 패턴, 문제 해결
agent-browser skills get core --full   # 전체 명령 참조와 템플릿까지
agent-browser skills list              # 이 버전에서 볼 수 있는 다른 가이드
```

아래는 그중 가장 자주 쓰는 흐름의 요약이다. 둘이 다르면 CLI가 알려 주는 쪽이 맞다.

화면을 확인하려면 개발 서버가 먼저 떠 있어야 한다. 서버를 띄우고 포트가 열렸는지 확인한 뒤 시작한다.

```bash
agent-browser open http://localhost:3000     # 주소 열기
agent-browser snapshot -i                    # 화면 구조를 @e1, @e2 같은 ref로 받기
agent-browser click @e2                      # ref로 클릭
agent-browser fill @e3 "test@example.com"    # ref로 입력
agent-browser screenshot shot.png            # 화면 캡처 (--full 이면 전체 페이지)
agent-browser close                          # 끝나면 반드시 닫기
```

| 하고 싶은 일 | 명령 |
|---|---|
| 화면 구조 + ref 얻기 | `agent-browser snapshot -i` |
| 클릭 / 입력 | `click <ref>` / `fill <ref> <text>` |
| 키 입력 | `press Enter` |
| 텍스트 읽기 | `get text <ref>` |
| 로딩 기다리기 | `wait --load domcontentloaded` |
| 캡처 | `screenshot [경로]`, 변화 없으면 건너뛰기: `--if-changed` |
| 번호 달린 캡처 | `screenshot --annotate` |

명령은 `&&`로 이어 붙일 수 있다. 브라우저는 백그라운드로 유지된다. 다만 snapshot 결과를 보고 ref를 골라야 하는 단계에서는 따로 실행한다.

### 알아 둘 동작

- **ref는 snapshot 시점의 것이다.** 화면이 바뀌면 `snapshot`을 다시 찍고 새 ref를 쓴다.
- 클릭 대상을 다른 요소(동의 배너, 모달)가 가리면 클릭이 실패하고 가리는 요소를 알려 준다. 그 요소를 먼저 처리하고 snapshot을 다시 찍은 뒤 재시도한다.
- 숨겨졌거나 비활성이거나 화면 밖인 요소는 "성공"으로 넘어가지 않고 실패한다. 실패를 무시하지 않는다.

## 4. 확인 결과 보고 형식

"다 됐습니다"만 쓰지 말고 아래를 채워서 보고한다.

1. 확인한 주소
2. 실제로 한 동작 (예: 가입 폼 채움 → 제출 클릭)
3. 결과: 성공 / 실패
4. 스크린샷 경로
5. 실패했다면 어디가 문제인지 (화면에서 본 것 기준)

## 지켜야 할 것

- 테스트용 값만 입력한다. 실제 계정, 비밀번호, 결제 정보를 입력하지 않는다.
- 사용자의 평소 크롬 프로필을 가져오거나(`--profile`) 이미 로그인된 크롬에서 로그인 상태를 가져오거나(`--auto-connect` + `state save`) 로그인 상태를 저장하는 기능은 사용자가 요청했을 때만 쓴다.
- 스크린샷에 비밀값(키, 토큰, 개인정보)이 찍히지 않았는지 보고하기 전에 확인한다.
- 작업이 끝나면 `agent-browser close`로 세션을 닫는다.
- 명령이 실패하면 "확인했다"고 보고하지 않는다. 실패한 명령, 원인, 다음 조치를 그대로 알린다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| `agent-browser: command not found` | `npm install -g agent-browser` 후 터미널 다시 열기 |
| 브라우저가 안 열림 | `agent-browser install` 실행 |
| 리눅스에서 실행 실패 | `agent-browser install --with-deps` |
| 클릭이 실패함 | 가리는 요소 처리 후 `snapshot` 재실행 |

공식 자료: <https://agent-browser.dev> · <https://github.com/vercel-labs/agent-browser>
