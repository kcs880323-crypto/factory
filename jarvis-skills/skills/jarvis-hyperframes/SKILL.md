---
name: jarvis-hyperframes
description: "Use when the user wants to make a video by writing HTML and rendering it to MP4 (launch/promo clips, explainers, captions, charts, vertical social clips) with HyperFrames by HeyGen, or asks to install or check it. Installs the pinned HyperFrames plugin and runs its CLI with consent gates for telemetry, feedback, publishing and HeyGen services. HTML로 영상을 만들어 MP4로 렌더링하는 HyperFrames 설치·사용 안내."
---

# HyperFrames: HTML을 쓰면 MP4가 나오는 영상 제작

HeyGen이 공개한 오픈소스(Apache-2.0)입니다. 영상을 HTML(과 GSAP 같은 애니메이션)로 쓰면, 헤드리스 Chrome이 프레임을 하나씩 찍고 FFmpeg가 MP4로 묶습니다. 같은 입력은 같은 영상을 만듭니다. 플러그인 하나에 스킬 7개가 들어 있습니다: `hyperframes`(규칙과 작업 순서), `hyperframes-cli`, `hyperframes-registry`(효과·전환·자막 카탈로그), `media-use`(음악·음성·이미지), `figma`, `slideshow`, `remotion-to-hyperframes`.

영상 제작 도구 5종 중 **가장 먼저 써 볼 것**입니다. 이 도구가 만든 HTML 영상은 사람이 같은 파일을 열어 고칠 수 있습니다.

## 0. 필요한 것

- **Node.js 22 이상**과 **FFmpeg** (렌더할 때 필요. 플러그인 설치만 할 때는 필요 없음)
- 인터넷: 첫 실행 때 npm에서 CLI(고정 버전)를 내려받고, 기본 템플릿은 렌더할 때 jsDelivr CDN에서 GSAP를 불러온다 (아래 5번).

## 1. 설치 여부 확인

```bash
claude plugin list          # hyperframes@hyperframes 가 enabled 이면 설치됨
node --version              # v22 이상
ffmpeg -version             # 렌더에 필요
```

이미 설치되어 있으면 **다시 설치하지 않는다.** `npx skills add heygen-com/hyperframes`나 `npx hyperframes skills update`로 같은 스킬을 또 설치하지 않는다 (플러그인과 중복되고, 이쪽은 `main`의 최신을 내려받는다. 플러그인을 설치했으면 스킬 관리는 플러그인이 맡는다).

## 2. 설치 (버전 고정)

```bash
bash <경로>/jarvis-skills/install.sh --with-video-skills          # 계정 전체는 --global 추가
```

수동으로 하려면 (`--scope user`는 계정 전체, `local`은 이 프로젝트만):

```bash
claude plugin marketplace add "heygen-com/hyperframes#v0.8.197" --scope user
claude plugin install hyperframes@hyperframes --scope user
```

- 자료의 명령은 `#v0.8.197` 없이 `claude plugin marketplace add heygen-com/hyperframes`다. 그러면 그때그때의 최신이 설치된다. 이 패키지는 **시험한 `v0.8.197`(커밋 `f51650d8`)로 고정**한다.
- 설치 중 **"Git LFS 포인터 파일 84개" 안내와 "목록의 패키지가 설치되지 않았다(bun.lock)" 메모가 나온다.** 시험에서 둘 다 나왔고, 그래도 `init`, `check`, `render`는 정상이었다. `git lfs pull`은 하지 않는다.
- 설치 후 Claude Code를 완전히 종료했다가 다시 켠다. 스킬은 `/hyperframes:hyperframes` 같은 이름으로 보인다.

## 3. 사용

**새 빈 폴더**에서 Claude Code를 열고 요청한다.

```text
/hyperframes:hyperframes 로 10초짜리 세로(9:16) 영상을 만들어 줘. 주제는 <내 주제>. 먼저 이야기 구성 세 가지를 글로만 보여 줘.
```

플러그인으로 설치했을 때 스킬 규칙에 따라 CLI는 플러그인의 실행기를 통해 부른다 (고정된 CLI 버전을 쓰기 위해). 새 프로젝트의 작업 순서는 다음과 같다.

| 단계 | 하는 일 |
|---|---|
| `init <폴더>` | 프로젝트 만들기 (1920×1080, 30fps 기본) |
| 작성 | `index.html`과 `compositions/`에 장면을 HTML로 작성 |
| `check --json` | 규칙 위반 점검. 오류는 모두 고치고 경고는 검토 |
| `preview` | 브라우저에서 미리보기. **사용자가 승인한 뒤에** 렌더 |
| `render` | `renders/`에 MP4 생성 |

렌더가 끝나면 **파일 경로, 길이, 해상도를 `ffprobe`로 확인해서** 알린다. "렌더했다"고만 말하지 않는다. 사용자의 승인 없이 최종 렌더를 하지 않는다 (사용자가 파일이나 "질문 없이 진행"을 요청한 경우는 제외).

## 4. 사용자에게 알리고 동의받을 것

| 항목 | 내용 | 자비스의 행동 |
|---|---|---|
| **텔레메트리(기본 켜짐)** | 명령 이름, 렌더 성능, 오류 이름, OS·CPU·메모리 형태, 구동 중인 에이전트 이름(`claude_code`) 등을 보낸다. 영상 내용, 프로젝트 이름, 로컬 경로, 환경변수 값은 보내지 않는다고 한다. | 첫 사용 전에 알리고 **끌지 물어본다.** 끄는 방법: `npx hyperframes telemetry disable` 또는 환경변수 `HYPERFRAMES_NO_TELEMETRY=1`·`DO_NOT_TRACK=1`. 끄면 단계적 배포(카나리) 대상에서도 빠진다. |
| **피드백 전송** | 스킬 규칙이 "렌더 성공 뒤 `hyperframes feedback`을 1회 보낸다"고 한다. 텔레메트리를 껐거나 사용자가 거절하면 보내지 않는다. `--file-issue`는 **공개 주소에 올라간다.** | 사용자가 동의하지 않았으면 보내지 않는다. `--file-issue`는 쓰지 않는다. |
| **`publish`** | 프로젝트를 외부 주소(HeyGen)에 업로드한다. | 사용자가 요청하고 올릴 내용을 확인한 뒤에만. `--public`·`--yes`는 쓰지 않는다. |
| **`auth login`** | HeyGen 계정과 사용 기록을 연결한다. | 사용자가 요청할 때만. |
| **`media-use`의 HeyGen 기능** | 아바타, 음성, 생성 영상 등은 HeyGen 서비스(요금 가능)를 쓸 수 있다. 무료 로컬 도구도 있다고 한다. | 무료 로컬 방법을 먼저 제안하고, 유료·외부 서비스는 사용자가 고른 경우에만. |

## 5. 알려진 한계

- **기본 템플릿은 렌더할 때 `cdn.jsdelivr.net`의 GSAP를 불러온다.** 네트워크가 막혀 있으면 렌더가 `sub_timeline_script_failure`로 막힌다 (시험 환경에서 실제로 발생). 해결: GSAP 파일을 프로젝트의 `assets/`에 넣고 `<script src="assets/gsap.min.js">`로 바꾼다 (이렇게 바꾸고 렌더가 성공함을 확인).
- 글꼴은 번들된 것만 이름으로 쓸 수 있다. 다른 글꼴은 `assets/fonts/`에 파일을 넣고 `@font-face`를 쓴다. **한글 글꼴은 따로 정해야 한다.**
- 사진, 로고, 음악, 음성은 **출처와 사용 권리**를 사용자에게 알린다. 실제 제품·브랜드를 보여 줄 때는 그 실제 화면·로고를 쓰고 지어내지 않는다.
- 플러그인은 **자동 업데이트하지 않는다.** 새 버전은 사용자가 원할 때 `claude plugin update`로 올리고 재시작한다. 올릴 때는 `check`로 기존 프로젝트가 여전히 통과하는지 확인한다.

## 지켜야 할 것

- 텔레메트리·피드백·게시·로그인·HeyGen 유료 기능은 **사용자 동의 후에만.** 한 번의 동의가 다른 항목으로 이어지지 않는다.
- 프로젝트 폴더 밖의 파일을 수정하지 않는다. 플러그인 폴더는 읽기 전용으로 다룬다.
- 기존 프로젝트의 `package.json`·락 파일을 플러그인 버전에 맞추려고 덮어쓰지 않는다. 버전이 어긋나면 알리고 묻는다.
- 렌더 결과를 직접 확인(프레임 몇 장 보기, 길이·해상도 확인)한 뒤 보고한다. 확인하지 못한 것은 확인하지 못했다고 쓴다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| `Render blocked … sub_timeline_script_failure` | 위 5번: CDN 접속 문제. GSAP를 로컬 파일로 |
| `ffmpeg` 를 찾을 수 없음 | FFmpeg 설치 (윈도우: `winget install Gyan.FFmpeg` 후 새 터미널) |
| `npx` 가 오래 멈춤 | 첫 호출은 CLI를 내려받는다. 네트워크가 npm 레지스트리를 막는지 확인 |
| 스킬이 안 보임 | Claude Code 완전 종료 후 재시작. `claude plugin list` 확인 |
| 같은 스킬이 두 번 보임 | `npx skills add`나 `skills update`로 따로 설치한 것. 플러그인만 남기고 정리 (삭제 전 사용자 확인) |

시험한 범위: 설치, `init`, `check`, `render`(1920×1080, 30fps, 10초 H.264 MP4 생성). 시험하지 못한 범위: 윈도우, 데스크톱 앱(Studio), `media-use`의 HeyGen 기능, `publish`.

공식 자료: <https://github.com/heygen-com/hyperframes> · <https://hyperframes.heygen.com>
