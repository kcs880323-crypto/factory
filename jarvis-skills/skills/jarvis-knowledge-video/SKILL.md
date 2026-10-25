---
name: jarvis-knowledge-video
description: "Trigger phrase: 지식영상 생성 (knowledge video). Use when the user says '지식영상 생성' (optionally with a topic or length), or asks for a 35-45 second Korean knowledge-sharing explainer video (30 s body plus the fixed Charles opening and ending) with voice narration and captions like the bee waggle dance video. Picks a topic (given or random, no repeats), writes a fact-checked script, synthesizes Korean narration locally, builds a 1920x1080 video with the HyperFrames plugin, verifies it by measurement, and delivers the MP4. 지식 공유 짧은 영상(나레이션+자막) 자동 제작."
---

# 지식영상 생성: 찰스의 30초 지식 영상 만들기

사용자가 **"지식영상 생성"**이라고 말하면 이 절차대로 영상 한 편을 만듭니다. 주제를 같이 말하면(예: "지식영상 생성: 무지개") 그 주제로, 말하지 않으면 **내가 고릅니다.** 한국어 나레이션과 자막, 1920×1080, 30fps, 설명이 한 장면씩 자라는 본편에 **찰스의 고정 오프닝과 엔딩**이 앞뒤로 붙고, 전체 길이는 **35~45초**(본편 낭독 25초 안팎 + 오프닝·엔딩 약 11초)입니다. 첫 예시는 「꿀벌의 8자 춤」(30초, 오프닝·엔딩 도입 전)과 「문어의 심장은 세 개」(44초, 찰스 포함)입니다.

## 찰스 (채널 캐릭터)와 고정 문구

채널 이름은 **찰스**, 캐릭터 **찰스**는 노란 콩 모양 몸통에 둥근 뿔테 안경, 머리 위 아이디어 안테나 세 가닥, 남색 나비넥타이를 한 지식 전도사입니다 (`assets/charles/charles-sheet.png`, `charles.svg`). 문구는 **사용자가 정한 것이라 한 글자도 바꾸지 않는다.**

| 구간 | 문구 | 화면 |
|---|---|---|
| 오프닝 | **"30초만 주면, 지식 하나 드릴게요. 찰스입니다!"** | 찰스가 손을 흔들며 말하고, "30초" → "지식 하나" → 전구(아이디어) → "찰스입니다!" 판이 낭독에 맞춰 등장 |
| 엔딩 | **"알아두면 언젠가 쓴다! 오늘의 지식공유가 유익했다면 좋아요와 구독, 찰스였습니다!"** | "알아두면 언젠가 쓴다!" → 점선 자리에서 "좋아요"(엄지) → "구독"(종 울림) 버튼이 채워짐 → "찰스였습니다!"를 손 흔들며 2초 이상 유지 |

- 찰스의 **입은 낭독 소리 크기에 맞춰** 프레임마다 벌어진다 (`charles.mouth_track`). 눈 깜빡임, 몸 흔들림, 손 흔들기는 유한 횟수 트윈이다.
- 오프닝·엔딩 장면은 `scripts/charles.py`의 `write_scenes`가 만든다. 주제마다 새로 그리지 않는다. 본편 색과 상관없이 찰스 고유의 남색 바탕과 노란색을 쓴다.
- 오프닝·엔딩 약 11초가 고정이므로 **본편 낭독은 약 25초 이내**(공백 제외 150~200자)로 쓴다. 40초 안에 끝내고 싶으면 본편을 22초 안팎으로 줄인다.

이 스킬은 **HyperFrames 플러그인**(`jarvis-hyperframes` 스킬, `install.sh --with-video-skills`)을 쓴다. 영상 제작 규칙은 그 플러그인의 `/hyperframes:hyperframes`가 가진 것이 우선한다.

## 0. 처음 한 번만: 사용자에게 알리고 동의받을 것

`~/.jarvis-knowledge-video` 폴더가 **없을 때만** 다음을 알리고 진행해도 되는지 묻는다. 이미 있으면 다시 묻지 않는다.

| 항목 | 내용 |
|---|---|
| 내려받는 것 | 한국어 음성 모델 약 67MB(GitHub 릴리스, SHA-256 확인), 파이썬 패키지(sherpa-onnx 1.13.8 등, 가상환경에만), npm 패키지(gsap 3.14.2, pretendard 1.3.9), HyperFrames CLI 0.8.197(npm) |
| 설치 위치 | `~/.jarvis-knowledge-video` (계정 전체 위치. 폴더를 지우면 모두 없어진다) |
| 음성 모델 라이선스 | KSS 한국어 데이터로 학습된 모델이라 **비상업 조건일 가능성이 높다**(모델 라이선스 파일은 확인하지 못했다). 영상을 회사·수익 활동에 쓸 거면 먼저 확인하게 한다. |
| 외부로 나가는 것 | 없음. 음성·그림 모두 내 컴퓨터에서 만든다. HyperFrames 텔레메트리는 **끈 상태**(`HYPERFRAMES_NO_TELEMETRY=1`, `DO_NOT_TRACK=1`)로 실행하고, 피드백 전송·`publish`·`auth login`은 하지 않는다. |
| 필요한 도구 | Node.js 22+, ffmpeg, Python 3.10+ (**시스템 도구는 자동 설치하지 않고** 없으면 알린다) |

## 1. 주제 정하기

1. 사용자가 준 주제가 있으면 그것. 없으면 무작위로 고른다. **`knowledge-videos/ledger.json`**(없으면 만든다)에 이미 만든 주제가 있으면 겹치지 않게 한다.
2. 좋은 주제: **한 가지 원리를 한 문장으로 말할 수 있고**, 그림으로 단계마다 보여 줄 수 있는 것 (자연, 우주, 몸, 날씨, 동물, 역사 속 발견, 일상 속 과학).
3. 피할 것: 의학·법률·투자 조언, 논란이 큰 정치·사회 쟁점, 실존 인물 비방, 불확실한 통계, 특정 상표·캐릭터가 중심인 주제.

## 2. 사실 확인 (건너뛰지 않는다)

- **확실히 아는 사실과 출처로 확인한 사실만** 대본에 쓴다. 이름, 연도, 숫자는 특히 확인한다.
- 웹 검색이 가능하면 핵심 주장마다 신뢰할 만한 출처 하나 이상을 찾아 **`SOURCES.md`**(주장 → 출처 주소)에 적는다. 가능하지 않으면 "출처는 확인하지 못했다"고 보고에 쓴다.
- 확인되지 않는 부분은 빼거나 "~로 알려져 있다"로 쓴다. 그럴듯하게 지어내지 않는다.

## 3. 대본(본편): 5줄, 절(clause) 12개 안팎

| 줄 | 역할 | 예(꿀벌) |
|---|---|---|
| 1 | 훅: 질문 한 개 | 벌집 안은 깜깜합니다. 꿀이 있는 곳을 어떻게 알려 줄까요? |
| 2 | 정답 | 정답은 춤입니다. |
| 3 | 원리 (핵심 도표가 자란다) | 직선 구간의 방향이 태양 기준 방향입니다. |
| 4 | 한 가지 더 | 흔드는 시간이 길수록 멉니다. |
| 5 | 마무리: 한 문장 + 출처 있는 사실 | 이 발견으로 카를 폰 프리슈는 1973년 노벨상을 받았습니다. |

- 본편 낭독문은 공백 제외 **약 150~200자**(문어 영상: 본편 낭독 약 25초, 속도 1.25). 줄마다 **절을 짧게 끊어** 화면이 낭독에 맞춰 등장할 단서가 되게 한다.
- `clauses.json`에 절마다 `spoken`(소리 내어 읽는 글)과 `caption`(자막)을 따로 쓴다. 숫자와 기호는 읽는 형태로: `1973년` → `천구백칠십삼 년`, `8자` → `팔자`. 자막에는 원래 표기를 쓴다. 본편만 쓴다 (오프닝·엔딩 절은 다음 단계에서 붙는다). 예시: `reference/octopus-hearts/clauses.json`.
- 화면의 글을 낭독이 그대로 반복하지 않는다. 화면은 **그림**이 말하고, 글은 라벨 정도만.

## 4. 음성과 시각표

```bash
S=<이 스킬 폴더>/scripts            # 설치된 jarvis-knowledge-video/scripts
python "$S/setup_tts.py"            # 처음 한 번 (이미 있으면 아무것도 받지 않음). 마지막 줄 PY=…, MODEL=… 를 읽는다
PY=<위의 PY>; MODEL=<위의 MODEL>
"$PY" -I "$S/make_clauses.py" clauses.json clauses-full.json            # 본편 앞뒤에 찰스의 오프닝·엔딩 절을 붙인다
"$PY" -I "$S/tts_clauses.py" "$MODEL" clauses-full.json clauses 1.25    # 절마다 wav
"$PY" -I "$S/build_track.py" clauses-full.json clauses audio           # audio/ 에 narration.wav, timing.json(자막), clauses_timing.json(절별), parts.json(오프닝·본편·엔딩), duration.txt
```

- `build_track.py`가 영상 길이(35~45초)를 정해 `duration.txt`에 쓴다. 마지막 장면을 2초 이상 보여 주려고 낭독이 끝난 뒤 2초를 더한다. **낭독이 43초를 넘어 실패하면** 본편 대본을 줄이거나 속도를 올린다 (속도는 선형이 아니다: 1.0→1.25는 약 11% 짧아졌다). 속도 1.4 이상은 알아듣기 어려울 수 있다.
- 나레이션은 **`loudnorm`으로 −16 LUFS**에 맞춰진다. 영상에 넣을 때는 `data-volume="0.85"`.

## 5. 화면 만들기 (HyperFrames)

```bash
export HYPERFRAMES_NO_TELEMETRY=1 DO_NOT_TRACK=1
node "<PLUGIN_ROOT>/skills/hyperframes/scripts/plugin-cli.mjs" init <slug> --skip-skills --non-interactive
"$PY" -I "$S/prepare_assets.py" <slug>      # gsap.min.js(로컬), Pretendard woff2 3종 (CDN 쓰지 않음)
cp audio/narration.wav audio/timing.json audio/clauses_timing.json audio/parts.json audio/duration.txt <slug>/assets/
```

`<PLUGIN_ROOT>`는 `jarvis-hyperframes` 스킬의 방식으로 찾는다. 그다음 참고 예시의 구조를 가져와 이 주제의 장면을 만든다 (그대로 복사하지 않는다).
- `reference/bee-dance/build_video.py`: 장면을 갈아 끼우는 구성(꿀벌이 장면 사이를 이어 감), 길 따라 움직이기, 점선 그리기.
- `reference/octopus-hearts/build_video.py`: **찰스 오프닝·엔딩이 붙은 완성형.** 한 장면이 15초 동안 하나의 도표를 키우는 구성(문어 몸속에 장기가 하나씩 등장), 피 알갱이 흐름(`offset-path`), 심장 박동 함수(`beats`), 장면 3개. **본편 장면은 본편 시각(전체 시각에서 `OFF`를 뺀 값)으로 쓰고 호스트 `data-start`에만 `OFF`를 더한다** (오프닝 길이가 바뀌어도 장면 코드를 안 고친다). `charles.write_scenes(proj, 절별 시각, narration.wav, 전체 길이, OFF)`가 오프닝·엔딩 호스트를 돌려준다.
- `reference/bee-dance/`는 오프닝·엔딩 도입 **전**의 예시다 (장면 구성과 기법만 참고).

**구성:** 배경(`bg`) + 칩(주제 이름, 상단 좌측) + **장면 3~5개** + 자막(`captions`) + 오디오 1개. 장면은 `compositions/<이름>.html` 하위 구성으로, 호스트 id = 장면 root의 `data-composition-id` = 타임라인 키가 같아야 한다.

**연출 규칙**
- **목소리가 시계다.** 각 요소는 `clauses_timing.json`의 절 시작 시각(본편 시각 = 전체 시각 − `OFF`)에 맞춰 등장한다. 한 장면의 모든 것이 처음에 다 나와 있으면 슬라이드가 된다.
- **하나의 도표가 자란다.** 가장 단순한 그림에서 시작해 낭독이 부분을 말할 때마다 하나씩 더한다. 장면이 바뀌어도 같은 물체를 이어서 쓴다(꿀벌: 벌이 장면 1에서 3까지 그대로 춤을 춘다).
- 색: 어두운 바탕 + 주제 색 1개(꿀벌: 꿀색 `#F6B21A`) + 크림색 글자. 글꼴 Pretendard(800/700/400). **글자는 28px 이상**, 자막은 56px 700, 하단 `top: 884px`.
- 마지막 한 줄(핵심 요약)은 낭독이 끝나기 전에 띄워 **2초 이상 유지**한다. 브랜드 끝 카드는 없다.
- 장면 전환: 다음 장면을 0.3초 겹쳐 시작하고 앞 장면은 불투명도·블러로 사라진다. 같은 요소는 같은 위치에서 이어 받는다.
- 모든 그림은 **코드로 그린다**(SVG/CSS). 외부 이미지, 로고, 상표 캐릭터, 실존 인물 사진은 쓰지 않는다.

**기술 규칙 (시험하며 확인한 것)**
- 장면 안에서는 `fromTo`를 쓴다. 같은 대상에 `fromTo`를 여러 번 쓰면 `immediateRender: false`를 주고, 쉬는 상태는 `tl.set(..., 0)`으로 만든다 (아니면 마지막 `from` 값이 처음부터 적용된다).
- 점선을 그려 넣을 때는 **마스크 경로**를 쓴다(점선 자체의 `strokeDashoffset`을 움직이면 점이 흘러갈 뿐이다). 실선은 `pathLength="100" stroke-dasharray="100 120"`, `strokeDashoffset 100→0` (간격이 길이보다 길어야 둥근 끝점이 안 보인다).
- 길을 따라 움직이는 물체는 CSS `offset-path: path(...)` + `offsetDistance` 트윈. 몸을 흔들거나 날갯짓은 `repeat`를 **유한 횟수**로(`repeat: -1` 금지). `onUpdate` 같은 콜백으로 화면을 바꾸지 않는다.
- **위치를 정하지 않은 `position:absolute` 0×0 요소는 화면 왼쪽 위(0,0)에 남아 작은 점으로 보인다** (일시정지 표시가 그랬다). 움직이는 물체 안의 부품(심장, 표시)은 그 물체의 자식으로 넣어서 함께 움직이게 한다.
- 파이썬 문자열 서식(`%`)과 CSS의 `%`(`offset-distance: 0%`)가 충돌한다. 자리표시자는 `__이름__` + `.replace()`로.
- 렌더 때 **CDN을 쓰지 않는다** (GSAP는 `assets/gsap.min.js`). 글꼴은 각 장면 파일 안에서 `@font-face`.
- 장면 겹침, 자막 위치, 좌표 계산은 `snapshot`으로 눈으로 확인하고 고친다.

## 6. 점검과 렌더 (이 순서)

```bash
node "<PLUGIN_ROOT>/skills/hyperframes/scripts/plugin-cli.mjs" check --json > check.json
"$PY" -I "$S/check_summary.py" check.json        # 오류 0 이어야 한다. 경고·정보는 검토
node "<PLUGIN_ROOT>/skills/hyperframes/scripts/plugin-cli.mjs" snapshot --at <t1,t2,…>   # 장면마다 2~3개 시점을 직접 본다
node "<PLUGIN_ROOT>/skills/hyperframes/scripts/plugin-cli.mjs" render
"$PY" -I "$S/verify_video.py" renders/<파일>.mp4 assets/timing.json
```

- `snapshot`에서 **글이 겹치거나 잘리거나 어긋난 것, 장면 전환의 튐**을 찾아 고친 뒤 렌더한다. 렌더는 몇 분 걸린다(30초 영상이 GPU 없는 환경에서 약 4분).
- `verify_video.py`가 불합격이면 보고하기 전에 고친다 (소리 크기가 −13 LUFS보다 크면 `data-volume`을 낮추고 다시 렌더하거나 오디오만 다시 묶는다).
- **소리는 들을 수 없다.** 측정한 것(길이, 크기, 절마다 소리 있음)과 듣지 못한 것(발음, 억양)을 구분해서 보고한다.

## 7. 납품과 기록

- 결과: `knowledge-videos/<slug>/` 안에 프로젝트, `SOURCES.md`, 렌더한 MP4. 파일 이름은 `<slug>-<날짜>.mp4`.
- 파일을 사용자에게 전달한다 (파일 전송 도구가 있으면 그것으로, 없으면 경로를 알린다).
- `knowledge-videos/ledger.json`에 `{주제, 날짜, 길이, 파일}`을 추가한다 (다음에 같은 주제를 고르지 않기 위해).
- 보고 형식: 주제 / 길이 / **확인한 것**(규칙 점검, 측정 결과) / **확인하지 못한 것**(발음·억양, 출처 미확인 항목) / 출처 / 음성 모델 라이선스 한 줄.

## 지켜야 할 것

- **오프닝·엔딩 문구와 찰스의 모습은 사용자가 정한 것이다.** 바꾸지 않는다. 바꾸고 싶으면 사용자에게 제안하고 `reference/charles/*.json`과 `assets/charles/`를 함께 고친다.
- 사실은 확실한 것만. 확인 못 한 것을 그럴듯하게 쓰지 않는다.
- 동의 없이 외부 서비스(HeyGen, MaxFusion, 온라인 음성 합성 등)를 쓰지 않는다. 텔레메트리를 켜지 않고, 피드백 전송·`publish`·`auth login`을 하지 않는다.
- `~/.jarvis-knowledge-video`와 `knowledge-videos/` 밖의 파일을 고치지 않는다. 기존 영상·프로젝트를 덮어쓰지 않는다 (같은 이름이면 `-2`를 붙인다).
- 다른 사람의 목소리를 흉내 내지 않는다. 음성은 이 스킬의 로컬 모델만.
- 최종 렌더 전에 `check` 오류 0과 `snapshot` 확인을 마친다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| `setup_tts.py`가 모델 SHA-256 불일치로 중단 | 릴리스 파일이 바뀐 것. 내용을 확인하고 사용자 승인 후 해시를 갱신 |
| 음성이 비어 있다 / `no frontend available` | sherpa-onnx가 1.13.8이 아닌 경우(2.0.0은 이 모델에서 실패). `setup_tts.py`를 다시 실행 |
| `build_track.py`가 "낭독이 N초" 로 실패 | 대본을 줄이거나 속도를 올린다 |
| 렌더가 `sub_timeline_script_failure` | GSAP가 CDN을 쓰고 있다. `assets/gsap.min.js`로 |
| 자막이 서로 겹침 | 절 사이 간격(`--intra`, `--inter`)을 늘리거나 자막 클립 길이를 줄인다 |
| `ffmpeg`/`npm`이 없다 | 사용자에게 알려 설치하게 한다 (윈도우: `winget install Gyan.FFmpeg`) |

시험한 범위: 리눅스에서 `setup_tts.py`를 빈 상태에서 실행, 음성 합성, `build_track.py`(35~45초 자동 결정, 자막 합치기, 오프닝·본편·엔딩 구간), `make_clauses.py`, `prepare_assets.py`, 꿀벌 영상(30초)과 **찰스 오프닝·엔딩이 붙은 「문어의 심장은 세 개」(44초)**를 처음부터 끝까지 만들어 `check` 오류 0, `snapshot` 눈 확인, 렌더, `verify_video.py` 전부 합격(−14.7 LUFS, 절 마다 소리 있음). 시험하지 못한 범위: **윈도우**, 음성의 발음·억양과 **입 모양이 소리와 맞게 움직이는지**(캡처한 프레임에서 입이 벌어진 장면만 확인), 음성이 "찰스"라는 캐릭터에 어울리는지(KSS 모델은 여성 화자로 알고 있음), 웹 검색 원문 페이지 직접 열람.

공식 자료: <https://github.com/k2-fsa/sherpa-onnx> · <https://github.com/heygen-com/hyperframes> · Pretendard <https://github.com/orioncactus/pretendard>
