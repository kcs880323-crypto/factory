---
name: jarvis-claude-animation-base
description: "Use when the user wants a hand-painted, cartoon-style animation of the character Clawd (p5.js + p5.brush, rendered to MP4 with headless Chrome and ffmpeg) using the Claude Animation Base starter kit, or asks to set it up. Clones a pinned commit into a folder the user chooses and installs from the lockfile without install scripts. 손그림 느낌 캐릭터(Clawd) 애니메이션 스타터킷(Claude Animation Base) 설치·사용 안내."
---

# Claude Animation Base: 손그림 카툰 캐릭터 애니메이션

John Heibel이 공개한 스타터 킷(MIT)입니다. **Claude Code 스킬이 아니라, 복제해서 그 폴더에서 Claude Code를 여는 프로젝트**입니다. p5.js와 p5.brush(붓 느낌)로 캐릭터 **Clawd**를 그리는 코드, 규칙·작업 순서를 적은 `ANIMATION_GUIDE.md`, 헤드리스 Chrome + ffmpeg로 MP4를 만드는 `render.mjs`가 들어 있습니다. 모델이 **콘티를 먼저 쓰고 → 장면별로 만들고 → 컨택트 시트로 스스로 확인한 뒤 → `out/video.mp4`를 만듭니다.**

## 0. 먼저 알릴 것

| 항목 | 내용 |
|---|---|
| **모델 의존** | README 기준 시험 영상은 모두 Claude Opus 5.5의 `xhigh` 추론으로 만들었다. 추론 수준이 높을수록 장면이 화려하고 세밀해진다고 한다. 다른 모델·낮은 수준에서는 결과가 다를 수 있다. |
| **느린 렌더** | **GPU가 없으면** 수채화 채우기(p5.brush) 때문에 프레임당 수 초~수십 초가 걸린다. 시험 환경(GPU 없음, 소프트웨어 렌더)에서는 프레임 하나에 약 55초였다. GPU 없는 PC라면 모델에게 수채화 채우기를 다른 표현으로 바꾸게 하라고 README가 권한다. |
| **캐릭터** | Clawd는 Claude Code의 마스코트다. 저장소는 코드와 문서에 MIT를 명시하지만, **캐릭터 자체의 상표·저작권 취급은 저장소에서 확인하지 못했다.** 공개·상업적 용도 전에 사용자가 확인해야 한다. |
| **글꼴** | `studio.html`이 Google Fonts(Permanent Marker)를 불러온다. 인터넷이 막히면 글꼴만 달라진다 (영상에는 글자를 넣지 않는 것이 이 킷의 규칙이다). |

## 1. 필요한 것

- Node.js, **Google Chrome**, `ffmpeg`, `git`
- 윈도우는 `render.mjs`가 `C:\Program Files\Google\Chrome\Application\chrome.exe`와 `(x86)` 경로를 자동으로 찾는다. 다른 위치면 `--chrome=<경로>` 또는 환경변수 `CHROME_PATH`.

## 2. 설치 (고정 커밋, 락 파일 기준)

설치 스크립트는 이 도구를 설치하지 않는다 (프로젝트 폴더가 필요하기 때문). **사용자가 영상 프로젝트를 만들 위치를 정하게 한다** (기본 제안: 현재 폴더의 `claude-animation-base`). 이미 폴더가 있으면 덮어쓰지 않고 멈춘다.

```bash
mkdir claude-animation-base && cd claude-animation-base
git init -q && git remote add origin https://github.com/JohnHeibel/ClaudeAnimationBase.git
git fetch --depth 1 origin 0ac8bf2b31942376cb6b8c4074715595d512acd2     # 시험한 커밋
git checkout FETCH_HEAD
npm ci --ignore-scripts          # package-lock.json 기준 (README의 npm install 대신)
```

- 자료와 README의 `npm install`은 버전 범위(`^`)를 따라 새 버전을 받을 수 있다. 락 파일이 있으므로 **`npm ci`**로 같은 버전을 받는다 (46개 패키지, 설치 스크립트가 있는 패키지는 없음을 확인했다. 그래도 `--ignore-scripts`를 붙인다).
- 설치 후 **그 폴더에서** Claude Code를 연다 (`cd claude-animation-base && claude`).

## 3. 사용

```text
ANIMATION_GUIDE.md 를 읽고, Clawd 가 나비를 잡으려고 하는 15초짜리 영상을 만들어 줘.
```

- 가이드의 규칙: 손으로 그린 느낌, 살아 있음, 한 덩어리, 글자 없음, 전환은 항상, 장면마다 무슨 일이 생길 것, 평면 2D와 끓는 선(boiling linework).
- **먼저 콘티(장면 구성)를 사용자에게 보여 주고** 승인받은 뒤 코딩한다.
- 렌더 명령:

```bash
node render.mjs --clip --out=out/video.mp4            # 데모 11초 (src/scenes/demo.js)
node render.mjs --sheet=0,3 --out=out/sheet.jpg       # 지정한 시각의 컨택트 시트(빠른 확인)
```

- GPU가 없는 PC에서는 `--soft-gl`을 붙인다 (느리다). **소프트웨어 렌더에서는 첫 화면 로딩이 기본 30초 제한을 넘겨 `Navigation timeout`으로 실패할 수 있다** (시험 환경에서 발생). 이럴 때는 `render.mjs`의 `page.goto(...)` 옵션에 `timeout`(예: 180000)을 사용자 동의 후 추가한다. 원본을 고친다는 점을 알린다.
- `studio.html`을 Chrome에서 열면 타임라인을 직접 훑어볼 수 있다. `?loop=emotions`, `?loop=views`로 모델 시트를 본다.

렌더가 끝나면 **`ffprobe`로 길이와 해상도를 확인하고**, 컨택트 시트에서 몇 장면을 눈으로 보고 보고한다. 렌더 시간이 길다면 먼저 시트로 확인한 뒤 전체 렌더를 시작한다.

## 지켜야 할 것

- **프로젝트 폴더를 사용자가 정한다.** 이미 있는 폴더에 덮어쓰지 않는다.
- 렌더는 시간이 오래 걸릴 수 있다. 전체 렌더 전에 예상 시간(프레임당 시간 × 프레임 수)을 알리고 확인받는다.
- 사용자의 사진·참고 이미지를 쓸 때는 권리를 확인한다.
- 설치된 코드를 사용자 요청 범위 밖으로 고치지 않는다 (장면은 `src/scenes/`에 새로 만든다).
- 캐릭터의 권리가 불분명하다는 점을 공개·상업 용도 전에 다시 알린다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| `Chrome not found` | Chrome 설치 또는 `--chrome=<경로>` / `CHROME_PATH` |
| `Navigation timeout of 30000 ms exceeded` | GPU 없음/느린 PC. `--soft-gl`과 위의 `timeout` 늘리기, 또는 GPU가 있는 PC |
| 화면이 검게 나오거나 WebGL 오류 | `--soft-gl`, 리눅스 GPU 서버는 `--gpu-angle=gl-egl` (`node gpu_probe.mjs <chrome 경로>`로 확인) |
| 프레임이 너무 느림 | 수채화 채우기를 다른 표현으로 바꾸게 요청, 해상도·길이 줄이기 |

시험한 범위: 고정 커밋 받기, `npm ci --ignore-scripts`, 데모 장면의 **컨택트 시트 2장(0초·3초) 렌더**(소프트웨어 렌더, 로딩 제한을 늘려서). 시험하지 못한 범위: 전체 MP4 렌더(ffmpeg 묶기), GPU 렌더, 윈도우, 모델이 새 장면을 만드는 과정.

공식 자료: <https://github.com/JohnHeibel/ClaudeAnimationBase> · p5.brush <https://github.com/acamposuribe/p5.brush>
