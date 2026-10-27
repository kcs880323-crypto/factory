# 지식영상 제작 매뉴얼 (자비스용, 2026-10-27판)

"지식영상 생성"이라고 하면 **찰스의 35~45초 한국어 지식 영상**을 만드는 방법을, Claude(클라우드 세션)가 실제로 만든 두 편(「문어의 심장은 세 개」 44.0초, 「꿀벌의 8자 춤」 43.0초)과 **같은 순서·같은 설정**으로 따라 할 수 있게 쓴 문서입니다. 자비스(당신)는 이 문서를 위에서부터 그대로 따르세요. 판단이 필요한 곳은 "판단" 표시와 이유를 적어 두었습니다.

> **이 문서가 하는 약속과 못 하는 약속**
> - 약속: 아래 순서와 값으로 하면 같은 종류의 영상(1920×1080, 30fps, 35~45초, 찰스 오프닝·엔딩, 자막, 한국어 나레이션)이 나옵니다.
> - 못 하는 약속: **소리는 들을 수 없으므로** 발음·억양이 좋은지는 사용자만 판단합니다. 합성에는 무작위가 있어 **같은 설정이어도 소리는 조금씩 다릅니다.** Claude는 **리눅스에서만** 시험했고 **Windows에서는 시험하지 못했습니다.**

> **파일 위치 대응:** 이 문서는 키트(zip, 폴더 이름 `knowledge-video-kit`) 안에 들어 있고 저장소(`kcs880323-crypto/factory`)의 `knowledge-videos/`에도 같은 내용이 있습니다.
> - 키트의 `examples/bee-waggle-dance/` = 저장소의 `knowledge-videos/bee-waggle-dance/`
> - 키트의 `examples/octopus-three-hearts/` = 저장소의 `knowledge-videos/octopus-three-hearts/`
> - 키트의 `tools/` = 저장소의 `knowledge-videos/tools/`
> - 이 문서는 키트에서는 `MANUAL.md`, 프롬프트는 `PROMPTS.md`입니다 (저장소에서는 `knowledge-videos/MANUAL.md`, `knowledge-videos/PROMPTS.md`).
> - 파일·폴더 이름을 영문으로 한 것은 Windows PowerShell 5.1이 압축 안의 한글 이름을 깨뜨릴 수 있기 때문입니다.

---

## 1. 완성품과 성공 기준

| 항목 | 기준 |
|---|---|
| 길이 | 35~45초 (낭독이 끝난 뒤 2초를 더한 값이 자동으로 정해짐) |
| 화면 | 1920×1080, 30fps, H.264, AAC 소리 |
| 구성 | 찰스 오프닝(약 4초) → 본편(약 26초, **그림 장면 3~5개**) → 찰스 엔딩(약 8초) |
| **오리의 자리** | **오리(찰스)는 오프닝과 엔딩에만 나옵니다.** 본편은 주제를 설명하는 그림·도표·숫자 장면으로 채웁니다. **오리가 본편 내내 나와서 설명하는 영상은 실패**이며 `check_frames.py`가 걸러냅니다 |
| 목소리 | Supertonic 3 목소리 5, 속도 1.2, natural (사용자가 듣고 고른 것) |
| 자막 | 낭독과 같이 절 단위로 나옴, 56px 700 굵기, 화면 아래 |
| 소리 크기 | 영상 −17~−13 LUFS (실제 −14.7~−15.0) |
| 점검 | `check` 오류 0 (경고는 1~2개까지, 이유를 확인), `verify_video.py` 전부 PASS |
| 파일 | `<slug>-charles-<날짜>.mp4` (약 14~21MB) |

**고정 문구는 한 글자도 바꾸지 않습니다** (사용자가 정함):
- 오프닝 "30초만 주면, 지식 하나 드릴게요. 찰스입니다!"
- 엔딩 "알아두면 언젠가 쓴다! 오늘의 지식공유가 유익했다면 좋아요와 구독, 찰스였습니다!"
- 찰스 그림(오리 박사)은 사용자가 만든 것이라 바꾸거나 새로 그리지 않습니다.

---

## 2. 준비 (한 번만)

### 2-1. 필요한 도구 (자동으로 설치하지 말고 없으면 사용자에게 알린다)

| 도구 | 기준 | 확인 | 없을 때 |
|---|---|---|---|
| Node.js | 22 이상 | `node --version` | nodejs.org |
| Python | 3.10 이상 | `python --version` (안 되면 `python3`, `py -3`) | python.org |
| FFmpeg와 ffprobe | 6 이상 | `ffmpeg -version`, `ffprobe -version` | `winget install Gyan.FFmpeg` 후 **새 터미널** |
| Claude CLI | 2.x | `claude --version` (PATH에 없으면 `~/.local/bin/claude.exe`) | 이미 사용 중 |
| 인터넷 | GitHub, npm, PyPI | | 첫 한 번만 필요 |

Windows에서는 **Git Bash**(Git for Windows)에서 아래 bash 명령을 씁니다. PowerShell용 대체 명령은 따로 적었습니다. **작업 폴더 이름과 `slug`는 영문·숫자·하이픈만** 쓰세요 (한글 경로는 도구마다 깨질 수 있습니다. 한글은 대본과 자막에만 씁니다).

### 2-2. 스킬 확인

`~/.claude/skills/jarvis-knowledge-video/` 안에 다음이 모두 있어야 합니다. 하나라도 없으면 **최신 jarvis-skills(스킬 33개)가 설치되지 않은 것**이니 멈추고 사용자에게 알리세요 (옛 15개 판에는 이 스킬이 없습니다).

```
SKILL.md
scripts/   setup_tts.py  make_clauses.py  tts_clauses.py  build_track.py  prepare_assets.py
           charles.py  charles_cutout.py  check_summary.py  verify_video.py
assets/charles/   pose-idea.png pose-wave.png pose-hug.png pose-like.png  charles.json  charles-sheet.png
reference/charles/   opening.json  ending.json
reference/octopus-hearts/   clauses.json  build_video.py
```

### 2-3. HyperFrames 플러그인 (v0.8.197 고정)

```bash
claude plugin list                    # hyperframes@hyperframes 가 enabled 이면 설치된 것 (다시 설치하지 않는다)
# 없을 때만 (사용자 동의 후):
claude plugin marketplace add "heygen-com/hyperframes#v0.8.197" --scope user
claude plugin install hyperframes@hyperframes --scope user
```

설치 중 "Git LFS 포인터 파일 84개"와 "bun.lock" 안내가 나오는데 **정상**입니다 (`git lfs pull`은 하지 않는다). 설치 후 Claude Code를 완전히 종료했다가 다시 켭니다.

영상 만드는 도구는 플러그인 안의 `plugin-cli.mjs`입니다. 위치를 찾아 변수로 둡니다:

```bash
# bash
CLI=$(find ~/.claude/plugins -name plugin-cli.mjs -path "*hyperframes/scripts*" | head -1); echo "$CLI"
```
```powershell
# PowerShell
$CLI = (Get-ChildItem "$env:USERPROFILE\.claude\plugins" -Recurse -Filter plugin-cli.mjs | Where-Object FullName -like "*hyperframes*scripts*" | Select-Object -First 1).FullName; $CLI
```

이후 모든 HyperFrames 명령은 `node "$CLI" <명령>`입니다 (`init`, `check`, `snapshot`, `render`). **`npx hyperframes …`를 직접 쓰지 않습니다** (고정 버전이 아니라 최신이 받아집니다).

### 2-4. 음성 준비 (처음 한 번만, 사용자에게 알리고 동의받기)

`~/.jarvis-knowledge-video` 폴더가 **없을 때만** 아래를 사용자에게 알리고 진행해도 되는지 묻습니다. 이미 있으면 다시 묻지 않습니다.

| 항목 | 내용 |
|---|---|
| 내려받는 것 | 한국어 음성 모델 Supertonic 3 약 128MB (GitHub 릴리스, SHA-256 확인), 파이썬 패키지(sherpa-onnx 1.13.8 등, 가상환경 안에만), npm 패키지(gsap 3.14.2, pretendard 1.3.9), HyperFrames CLI 0.8.197 |
| 설치 위치 | `~/.jarvis-knowledge-video` (폴더를 지우면 모두 사라짐) |
| 외부로 나가는 것 | 없음. 음성·그림 모두 내 컴퓨터에서 만든다. HyperFrames 텔레메트리는 끈 채 실행 |
| 라이선스 | Supertonic 3 코드 MIT, **모델 가중치 OpenRAIL-M** (상업 이용 가능하되 사용 제한 있음, 예: 사람을 속이는 용도 금지). 원문은 이 환경에서 열지 못했다 → 회사·수익 활동에 쓰면 huggingface.co/Supertone/supertonic-3 확인 |

```bash
S=~/.claude/skills/jarvis-knowledge-video/scripts
python "$S/setup_tts.py"      # 마지막 줄들의 PY=…  MODEL=… 을 읽어 둔다
```

PowerShell이면 `$S = "$env:USERPROFILE\.claude\skills\jarvis-knowledge-video\scripts"; python "$S\setup_tts.py"`. 출력의 `PY=` 값(가상환경 파이썬 경로)과 `MODEL=` 값(모델 폴더)을 변수로 둡니다. **이후 모든 파이썬 스크립트는 이 `PY`로, `-I`를 붙여 실행합니다** (`$PY -I "$S/…"`). 시스템 파이썬으로 실행하면 sherpa_onnx가 없어서 실패합니다.

Windows 참고: PyPI에 `sherpa-onnx 1.13.8`의 Windows 64비트용 설치 파일(Python 3.10~3.14)이 **있는 것은 확인했지만, 실제로 설치해 돌려 보지는 못했습니다.** 설치가 실패하면 오류를 그대로 사용자에게 알리고, Python 3.12로 다시 시도해 보자고 제안하세요.

### 2-5. 매 터미널마다 (텔레메트리 끄기)

```bash
export HYPERFRAMES_NO_TELEMETRY=1 DO_NOT_TRACK=1
```
```powershell
$env:HYPERFRAMES_NO_TELEMETRY = "1"; $env:DO_NOT_TRACK = "1"
```

피드백 전송, `publish`, `auth login`, HeyGen 유료 기능은 **쓰지 않습니다.**

### 2-6. 이 문서에서 쓰는 변수 (한 번 정해 두기)

| 변수 | 뜻 |
|---|---|
| `S` | 스킬의 scripts 폴더 (`~/.claude/skills/jarvis-knowledge-video/scripts`) |
| `PY`, `MODEL` | 2-4에서 읽은 값 |
| `CLI` | 2-3에서 찾은 `plugin-cli.mjs` 경로 |
| `slug` | 영상 폴더 이름 (영문 소문자·하이픈. 예: `bee-waggle-dance`) |
| `W` | 작업 폴더 = `knowledge-videos/<slug>/` (이 폴더 안에서 아래 명령을 실행) |

---

## 3. 작업 폴더 구조

```
knowledge-videos/
  ledger.json                  만든 주제 기록 (다음에 같은 주제를 고르지 않으려고)
  <slug>/
    clauses.json               본편 대본 (손으로 씀)
    clauses-full.json          오프닝·엔딩이 붙은 전체 (make_clauses.py)
    clauses/                   절마다 wav (tts_clauses.py 또는 고른 것)
    audio/                     narration.wav, timing.json, clauses_timing.json, parts.json, duration.txt
    proj/                      HyperFrames 프로젝트 (index.html, compositions/, assets/, renders/)
    spec.json                  본편 그림 장면 설계표 (방법 A, 권장)
    build_video.py             주제 맞춤 화면 생성기 (방법 B를 쓸 때만)
    SOURCES.md                 주장 → 출처
    publish.json               업로드용 제목·설명 (jarvis-publish 형식)
    <slug>-charles-<날짜>.mp4  결과 (저장소에 올리지 않음)
```

`proj/`, `clauses/`, `audio/`, `*.mp4`는 `.gitignore`로 **저장소에 올리지 않습니다.** 올리는 것은 `clauses.json`, `spec.json`(또는 `build_video.py`), `SOURCES.md`, `publish.json`과 `ledger.json`입니다.

---

## 4. 절차 (이 순서 그대로)

### 단계 1. 주제 정하기

- 사용자가 주제를 말하면 그것. 말하지 않으면 **`knowledge-videos/ledger.json`에 없는 주제를 무작위로** 고릅니다 (`ledger.json`이 없으면 만든다). 이미 만든 주제: 꿀벌의 8자 춤, 문어의 심장은 세 개.
- 좋은 주제: **한 가지 원리를 한 문장으로 말할 수 있고**, 그림으로 단계마다 보여 줄 수 있는 것 (자연, 우주, 몸, 날씨, 동물, 역사 속 발견, 일상 속 과학).
- 피할 것: 의학·법률·투자 조언, 논란이 큰 정치·사회 쟁점, 실존 인물 비방, 불확실한 통계, 특정 상표·캐릭터가 중심인 주제.

### 단계 2. 사실 확인 (건너뛰지 않는다)

- **확실히 아는 사실과 출처로 확인한 사실만** 씁니다. 이름·연도·숫자는 특히 확인합니다.
- 웹 검색으로 핵심 주장마다 믿을 만한 출처를 찾아 **`SOURCES.md`**에 `주장 | 확인한 내용 | 출처 주소` 표로 적습니다. 예: `examples/bee-waggle-dance/SOURCES.md`.
- **원문을 직접 열어 읽지 못하고 검색 요약만 본 것은 그렇게 적습니다.** 확인 못 한 부분은 빼거나 "~로 알려져 있다"로 씁니다. 지어내지 않습니다.
- 그림에서 일부러 단순화한 것(예: 각도 40°는 예시)이 있으면 `SOURCES.md` 끝에 적습니다.

### 단계 3. 본편 대본 쓰기

**형식**: `clauses.json` — 절(clause) 12개 안팎, 5줄.

```json
[
 {"id":"1a","line":1,"spoken":"벌집 안은 깜깜합니다.","caption":"벌집 안은 깜깜합니다."},
 {"id":"1b","line":1,"spoken":"꿀이 있는 곳을 어떻게 알려 줄까요","caption":"꿀이 있는 곳을 어떻게 알려 줄까요?"},
 {"id":"2c","line":2,"spoken":"팔자 모양으로 춤을 춥니다.","caption":"8자 모양으로 춤을 춥니다."}
]
```
- `id`: `1a`,`1b`,`2a`… (줄 번호 + 알파벳). `op*`, `en*`는 쓰지 않는다 (오프닝·엔딩 전용).
- `spoken`: 소리 내어 읽는 글. **숫자·기호·영문은 읽는 형태로** (`천구백칠십삼 년`, `팔자`, `삼십 초`). 그렇지 않으면 음성이 틀리게 읽는다.
- `caption`: 화면 자막. 원래 표기(`1973년`, `8자`)를 쓴다.

**5줄의 역할**

| 줄 | 역할 | 꿀벌 예 |
|---|---|---|
| 1 | 훅: 질문 한 개 | 벌집 안은 깜깜합니다. 꿀이 있는 곳을 어떻게 알려 줄까요 |
| 2 | 정답 | 정답은 춤입니다. 꿀을 찾은 벌은 돌아와, 팔자 모양으로 춤을 춥니다 |
| 3 | 원리 (핵심 도표가 자란다) | 가운데 직선 구간에서 몸을 흔드는데, 그 방향이 태양 기준 꿀의 방향입니다. 벌집 벽의 위쪽은 태양입니다 |
| 4 | 한 가지 더 | 흔드는 시간이 길수록, 꿀이 있는 곳은 더 멉니다 |
| 5 | 마무리: 한 문장 + 출처 있는 사실 | 이 발견으로 카를 폰 프리슈는, 천구백칠십삼 년 노벨 생리의학상을 받았습니다 |

**분량 규칙 (중요)**: 본편 `spoken`은 **공백·문장부호를 뺀 글자가 125~160자**. 오프닝·엔딩이 약 12초라서 이 범위를 넘으면 45초를 넘습니다. 재 본 값: 115자→36초, 135자→39초, 143자→43.0초(꿀벌), 149자→41초, 158자→44.0초(문어), 168자→45초(한계). **상한 근처는 합성할 때마다 길이가 달라 실패할 수 있으니 여유를 둡니다 (145자 안팎 권장).**

글자 수 세기:
```bash
"$PY" -I -c "import json,re;c=json.load(open('clauses.json',encoding='utf-8'));print(sum(len(re.sub(r'[^가-힣a-zA-Z0-9]','',x['spoken'])) for x in c))"
```

**쓰는 법**: 절을 짧게 끊어 화면이 낭독에 맞춰 등장할 단서가 되게 한다. 화면의 글을 낭독이 그대로 반복하지 않는다(화면은 그림이 말하고 글은 라벨 정도).

### 단계 4. 음성과 시각표

```bash
# (1) 본편 앞뒤에 찰스의 고정 오프닝·엔딩을 붙인다
"$PY" -I "$S/make_clauses.py" clauses.json clauses-full.json

# (2) 절마다 음성을 6번씩 합성한다 (d0~d5). 한 번에 약 10초
mkdir -p runs
for i in 0 1 2 3 4 5; do "$PY" -I "$S/tts_clauses.py" "$MODEL" clauses-full.json runs/d$i; done

# (3) 절마다 가장 잘 들리는 것을 고른다 (4-1 참고. 건너뛰고 d0을 써도 된다)
#     건너뛸 때:  cp -r runs/d0 clauses
# (4) 이어 붙여 narration.wav 와 시각표를 만든다
"$PY" -I "$S/build_track.py" clauses-full.json clauses audio
cat audio/duration.txt       # 35~45 사이여야 한다 (초)
```
PowerShell이면 `for ($i=0; $i -lt 6; $i++) { & $PY -I "$S\tts_clauses.py" $MODEL clauses-full.json "runs\d$i" }`.

`tts_clauses.py`의 기본값이 곧 **고정 설정**입니다 (인자를 주지 않는다): 목소리 5, 속도 1.2, 톤 natural, 추론 5단계. 짧은 절(7글자 이하→속도 1.0 이하, 8~10글자→1.1 이하)은 자동으로 느려지고, 클립 앞뒤 무음은 앞 0.08초·뒤 0.15초만 남기고 잘립니다. `build_track.py`는 줄 사이 0.4초, 같은 줄 안 0.14초 간격을 두고 −16 LUFS(loudnorm)로 맞춥니다.

**실패하면**
- `build_track.py`가 "낭독이 N초"로 실패 → **본편 대본을 줄입니다.** 속도를 올리지 않습니다 (1.3부터 짧은 절이 뭉개짐).
- `tts_clauses.py`가 모델을 못 찾음 → `MODEL` 경로 확인.

#### 4-1. 절마다 가장 잘 들리는 것 고르기 (권장, 선택)

음성합성에는 무작위가 있어 같은 절도 매번 조금 다릅니다. **한국어 음성인식으로 받아쓰게 해서 원문과 가장 비슷한 것**을 고르면 틀리게 읽은 절을 피할 수 있습니다 (꿀벌: 오류 글자 35 → 25, 문어: 39 → 19).

```bash
# 음성인식 모델 (한 번만. 압축 파일 약 315MB, 풀면 약 340MB. 가상환경 밖에 풀어 둔다)
#   https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-korean-2024-06-24.tar.bz2
ASRDIR=<풀린 폴더>/sherpa-onnx-zipformer-korean-2024-06-24      # 안에 encoder-epoch-99-avg-1.int8.onnx 등이 있어야 함
ASRDIR=$ASRDIR CLAUSES=clauses-full.json "$PY" -I <키트>/tools/pick_best_take.py runs clauses
# 저장소(kcs880323-crypto/factory)를 받았다면 파일 위치: knowledge-videos/tools/pick_best_take.py
```
- `runs/` 안에는 **`d0`~`d5` 폴더만** 둡니다 (`d`로 시작하는 다른 파일이 있으면 오류).
- 출력의 "오류 합계 처음 → 고른 뒤"를 보고에 적습니다.
- **이것은 받아쓰기 오류이지 사람 귀의 평가가 아닙니다.** 이름 "찰스"는 인식기가 "철수·체스"로 자주 틀리므로 오프닝 끝말 "입니다"와 엔딩 끝말 "였습니다"가 있는지만 봅니다 (`pick_best_take.py`는 그 두 절에서 끝말이 안 들리면 오류에 5를 더해 떨어뜨립니다).

### 단계 5. HyperFrames 프로젝트 만들기

```bash
node "$CLI" init proj --skip-skills --non-interactive
"$PY" -I "$S/prepare_assets.py" proj          # gsap.min.js(로컬), Pretendard woff2 3종 (렌더 때 CDN을 쓰지 않는다)
cp audio/narration.wav audio/timing.json audio/clauses_timing.json audio/parts.json audio/duration.txt proj/assets/
```
PowerShell이면 `Copy-Item audio\narration.wav,audio\timing.json,audio\clauses_timing.json,audio\parts.json,audio\duration.txt proj\assets\`.

### 단계 6. 본편 그림 장면 만들기 (가장 중요한 단계)

> **먼저 못 박아 둡니다.** 오리(찰스)는 **오프닝(약 4초)과 엔딩(약 8초)에만** 나옵니다. 그 사이 **본편 약 26초는 오리 없이** 질문 → 흐름도·카드·막대 → 큰 숫자 → 요약 한 줄 같은 **그림 장면 3~5개**로 채웁니다. "오리가 화면에 계속 나와서 설명하는" 영상은 실패입니다. 오프닝·엔딩의 오리는 `charles.write_scenes()`가 알아서 만드니 **본편에서 오리를 따로 그리거나 넣지 않습니다.**

두 가지 방법이 있습니다. **A를 쓰세요.** (B는 A로 표현이 안 되는 움직임이 꼭 필요할 때만.)

#### 방법 A (권장): 설계표 `spec.json` 한 장 + `scene_kit.py` (코드를 쓰지 않는다)

`tools/scene_kit.py`가 설계표를 읽어 본편 장면과 배경·칩·자막·찰스 오프닝/엔딩까지 한 번에 만듭니다. 사람이 하는 일은 **5줄 대본을 장면으로 나누고 JSON을 채우는 것**뿐입니다.

**① 장면 종류 5가지**

| type | 쓰는 곳 | 필드 (핵심만) | 화면 |
|---|---|---|---|
| `question` | 줄 1: 훅(질문) | `headline`(큰 제목, `\n`으로 줄바꿈), `sub`+`sub_at`, `symbol`(아이콘) | 큰 제목, 위에 아이콘 배지, 아래 보조 문장 |
| `flow` | 과정·원인→결과 (노드 2~5개) | `nodes: [{label, note, icon, at}]` | 아이콘 원 → 점선 화살표 → 아이콘 원 … 절 시각마다 하나씩 나타나고 마지막은 빛남 |
| `points` | 사실 2~4개 나열 | `items: [{text, icon, at}]` | 카드가 하나씩 나타남 (아이콘 + 두 줄 글) |
| `compare` | 두 가지 비교, 크기 차이 | `axis`, `left`/`right: {title, value(0~1), label, at, label_at}` | 카드 두 장, 막대가 낭독에 맞춰 참 |
| `stat` | 숫자·연도·핵심 한 단어 | `name`, `big`, `label`, `note`, `recap` (+ 각각 `_at`, `_dt`) | 큰 글자(300px) + 설명 + 마지막 요약 한 줄 |

- 모든 `at`, `*_at`은 **본편 절 id**(`1a`, `3b` …)입니다. 요소는 그 절이 시작되고 `dt`초(기본 0.15) 뒤에 나타납니다. 낭독이 "시계"입니다.
- `icon`: `sun moon drop flame leaf heart star bolt cloud mountain magnet atom gear eye clock globe flower fish bee tree question check wave planet thermometer arrow-up book scale` 중 하나, 또는 `"text:Cu"`처럼 글자 1~4자.
- `theme`: 주제 색 한 가지(`accent`)와 어두운 바탕(`bg`, `bg2`), 보조색(`accent2`), 글자색(`text`). 꿀벌 `#FFB81C`/`#120d08`, 문어 `#F26B5B`/`#07121b`.
- **마지막 장면의 `recap`**(핵심 요약 한 줄)은 엔딩 전환까지 **2초 이상** 보여야 합니다. `scene_kit.py`가 모자라면 "주의" 줄로 알려 줍니다. `recap_at`/`recap_dt`를 앞으로 당기세요.

**② 만드는 순서**
1. `clauses.json`의 5줄을 **장면 3~5개**로 나눕니다. 규칙: 장면들의 `clauses`를 이어 붙이면 **본편의 절 전부가 순서대로 한 번씩** 나와야 합니다 (빠뜨리거나 순서가 바뀌면 `scene_kit.py`가 오류로 알려 줍니다).
2. 표처럼 줄의 역할에 맞는 종류를 고릅니다: 줄 1 → `question`, 줄 2~3 → `flow`/`points`, 줄 4 → `compare`/`points`, 줄 5 → `stat`(+`recap`).
3. 각 절이 말하는 순간 **화면에 새로 나타나는 것**을 한 가지씩 정해 `at`에 그 절 id를 씁니다.
4. `spec.json`을 쓰고 실행합니다 (예시: `examples/bee-waggle-dance/spec.json`, `examples/octopus-three-hearts/spec.json`):
   ```bash
   python <키트>/tools/scene_kit.py proj spec.json "$S"
   ```
   `장면 1 (question): 본편 0.0~5.0초, 절 1a, 1b` … `index ok: 본편 장면 5 개 + 찰스 오프닝·엔딩` 줄이 나와야 합니다.
5. 7단계(점검·snapshot)로 갑니다. 글이 길어 잘리거나 겹치면 `label`/`text`를 줄입니다 (노드 `label` 한 줄 10자 안팎, `points`의 `text` 두 줄 안).

**③ 한계 (정직하게)**: 틀이 정해진 템플릿이라 그림이 **아이콘 + 도형 + 글자**입니다. 벌이 8자로 날아다니는 것 같은 **주제 맞춤 움직임**은 못 만듭니다. 그런 연출이 꼭 필요하면 방법 B입니다.

#### 방법 B (고급): 주제 맞춤 화면 코드 `build_video.py`를 직접 쓰기

예시를 복사해서 이 주제의 장면으로 바꿔 씁니다 (그대로 복사하지 않습니다).
- 가장 가까운 예시: `examples/bee-waggle-dance/build_video.py` (장면 4개, 길 따라 움직이는 물체, 도표가 자라는 구성), `examples/octopus-three-hearts/build_video.py` (장면 3개, 한 장면이 15초 동안 하나의 도표를 키움, 박동, 흐름 입자).
- 상세 규칙은 6장, 파일 읽는 법은 7장.
- 실행: `python build_video.py proj "$S"` (두 번째 인수 = 스킬의 scripts 폴더). 끝에 `index ok`가 나옵니다.
- B를 써도 **본편에 오리를 넣지 않고** 9단계에서 `check_frames.py`로 확인합니다.

### 단계 7. 점검과 화면 확인 (렌더 전에 반드시)

```bash
cd proj
node "$CLI" check --json > ../check.json
"$PY" -I "$S/check_summary.py" ../check.json
```
- **오류 0이어야 합니다.** 경고·정보는 이유를 읽고 판단합니다. 꿀벌에서 나온 경고 1개 `connector_orphan … #dr-link`는 **마스크로 가린 점선이 있어서 나오는 거짓 경보**였고 화면은 정상이었습니다. 장면이 겹치는 0.3초에 나오는 `content_overlap` 정보도 정상입니다.

**화면을 직접 눈으로 봅니다** (점검은 "어떻게 보이는지"를 알려 주지 않습니다):

```bash
node "$CLI" snapshot --at 2.0,3.9,5.9,8.2,10.5,12.4,14.4,15.9
# 끝에 contact-sheet.jpg 경로가 나온다 → 그 이미지를 열어서 본다
```
- 시점은 **전체 영상 시각**입니다. 고르는 법: 오프닝 2~3개(`op1` 중간, `op2`), 본편 장면마다 2~3개(장면 시작 직후, 핵심 요소가 다 나온 때, 전환 직전), 엔딩 1~2개. 본편 시각 + `OFF`(= `parts.json`의 body.start − 0.5)가 전체 시각입니다.
- 볼 것: **글이 겹치거나 잘리거나 어긋났나, 장면 전환에서 튀나, 작은 점이 화면 왼쪽 위에 남았나(6장 함정), 자막 위치, 낭독과 그림의 순서.**
- 고칠 것이 보이면 `build_video.py`를 고쳐 다시 실행 → `check` → `snapshot`을 되풀이합니다. **눈으로 본 뒤에만 렌더합니다.**

### 단계 8. 렌더

```bash
node "$CLI" render            # proj/renders/proj_<날짜>.mp4 (GPU 없는 환경에서 43초 영상이 약 3.5~5분)
```
렌더가 `sub_timeline_script_failure`로 실패하면 GSAP가 CDN을 쓰고 있는 것입니다 (`assets/gsap.min.js`로 바꾼다).

### 단계 9. 측정과 렌더 결과 확인

```bash
"$PY" -I "$S/verify_video.py" renders/<파일>.mp4 ../audio/timing.json
```
- 모두 PASS여야 합니다: 1920×1080, h264 30fps, 오디오 있음, 길이 35~45.1초, 소리 −17~−13 LUFS, 절마다 소리 있음(RMS > −35 dBFS).
- 소리가 −13 LUFS보다 크면 `index.html`의 `data-volume`을 낮춰(`0.85` → `0.8`) 다시 렌더.
- **본편에 그림이 나오는지 자동으로 확인합니다** (오리만 나오는 영상을 걸러냄):
  ```bash
  "$PY" -I <키트>/tools/check_frames.py renders/<파일>.mp4 ../audio/parts.json
  ```
  모두 PASS여야 합니다: 오프닝·엔딩에 찰스 화면이 있고, **본편에 오리(노란 찰스 화면)가 10% 이하**, 본편 화면이 바뀌는 표본이 25% 이상, 안 바뀌는 구간이 6초 이하. 이것은 "그림이 있고 움직이는가"만 재며 **그림이 보기 좋은지는 재지 못합니다** (그건 snapshot을 눈으로 봅니다).
- **렌더한 MP4에서 직접 프레임을 뽑아 봅니다** (snapshot과 렌더 결과가 같은지):
  ```bash
  ffmpeg -v error -y -ss 30 -i renders/<파일>.mp4 -frames:v 1 -vf scale=960:-1 f30.png
  ```
  오프닝, 본편 중간, 엔딩(마지막 2초)을 봅니다.
- (선택) 렌더한 영상의 소리만 뽑아 오프닝·엔딩 끝말이 들어 있는지 받아쓰기로 확인할 수 있습니다 (오프닝 "찰스입니다.", 엔딩 "…였습니다").

### 단계 10. 납품과 기록

1. 결과 파일을 `knowledge-videos/<slug>/<slug>-charles-<날짜>.mp4`로 복사한다 (같은 이름이 있으면 `-2`를 붙인다. **기존 영상·프로젝트를 덮어쓰지 않는다**).
2. `SOURCES.md`를 마무리하고, **업로드용 `publish.json`**을 쓴다 (제목·설명·인스타 캡션·태그·해시태그·출처. 형식은 `examples/…/publish.json`. 사실은 `SOURCES.md`에서 확인한 것만, 출처는 실제로 확인한 주소만).
3. `knowledge-videos/ledger.json`에 `{주제, 날짜, 길이(초), 파일, 메모}`를 추가한다.
4. 파일을 사용자에게 전달한다 (파일 전송 도구가 있으면 그것으로, 없으면 경로).
5. **보고** (10장 형식). **이 스킬은 올리지 않는다.** 사용자가 "유튜브·인스타 업로드"라고 하면 `jarvis-publish`의 절차(상태 확인 → 올릴 내용 보여 주고 확인받기 → 허락 뒤에만 올리기)를 따른다.

---

## 5. 고정값 (바꾸지 않는 것)

| 구분 | 값 | 이유 |
|---|---|---|
| 오프닝·엔딩 문구 | 위 1장의 문구 | 사용자가 정함 |
| 오프닝 낭독문 | `"찰스입니다."` (자막은 `"찰스입니다!"`) | 느낌표가 붙으면 끝말 "입니다"가 삼켜짐 (속도 1.0에서 32번 중 25번 대 32번 모두) |
| 목소리 | Supertonic 3 목소리 5 | 사용자가 샘플 8개를 듣고 고름 |
| 속도 | 1.2 | 1.3으로 올렸더니 "찰스입니다"가 16번 중 2번만 들림 |
| 톤 | natural | 후처리하면 받아쓰기 오류가 늘었음 |
| 본편 글자 수 | 공백·문장부호 제외 125~160자 | 45초 한계 |
| 영상 크기·프레임 | 1920×1080, 30fps | |
| 소리 | 나레이션 −16 LUFS(loudnorm), 영상에 넣을 때 `data-volume="0.85"` | |
| 글꼴 | Pretendard 800/700/400, 글자 28px 이상, 자막 56px 700, 자막 위치 `top: 884px` | |
| GSAP | 3.14.2 로컬 파일 (`assets/gsap.min.js`) | CDN 금지 |
| 찰스 그림·오프닝·엔딩 장면 | `charles.write_scenes()`가 만든다 | 주제마다 새로 만들지 않는다 |

---

## 6. 화면 제작 상세 규칙

### 6-1. 시각표와 화면을 맞추는 공식 (반드시 이대로)

예시 `build_video.py` 맨 위에 있는 부분입니다. 새 주제에서도 **그대로 씁니다.**

```python
raw = {r["id"]: r for r in json.load(open(A / "clauses_timing.json", encoding="utf-8"))}   # 절별 시각 (전체 영상 기준)
parts = json.load(open(A / "parts.json", encoding="utf-8"))
TOTAL = float((A / "duration.txt").read_text().strip())
OFF = parts["body"]["start"] - 0.5      # 본편의 0초가 전체 영상의 몇 초인가 (본편 첫 절은 본편 시각 0.5초에 있다)
T = {k: {**v, "start": v["start"] - OFF, "end": v["end"] - OFF} for k, v in raw.items() if v["part"] == "body"}   # 본편 시각
ES = raw["en1"]["start"] - 0.45 - OFF   # 엔딩 장면이 시작하는 본편 시각
```
- **본편 장면 안의 모든 시각은 본편 시각(`T[...]["start"]` 등)으로 씁니다.** 장면 호스트(`index.html`)의 `data-start`에만 `OFF`를 더합니다. 그러면 오프닝 길이가 바뀌어도 장면 코드를 고치지 않습니다.
- 장면의 JS에서는 `g(t) = t − T0`로 본편 시각을 그 장면 안의 시각으로 바꿉니다 (`T0` = 장면 시작 본편 시각).
- **목소리가 시계입니다.** 요소는 해당 절의 `start` 근처(보통 +0.0~+0.6초)에 등장합니다. 한 장면의 모든 것이 처음부터 나와 있으면 슬라이드가 됩니다.

### 6-2. 장면 나누기

- 장면은 3~5개. **줄 경계**에서 나눕니다. 장면 N의 시작 = 그 줄 첫 절의 `start` − 0.3, 끝 = 다음 장면 시작 + 0.3 (0.3초 겹쳐서 앞 장면은 불투명도·블러로 사라지고 다음 장면이 이어 받는다). 마지막 장면은 `ES + 0.4`까지.
- 꿀벌: `D_START = 3a.start − 0.3`, `S3_START = 4a.start − 0.3`, `O_START = 5a.start − 0.3`, 장면 길이 `HIVE_DUR = D_START + 0.3` 등.
- **하나의 도표가 자랍니다.** 가장 단순한 그림에서 시작해 낭독이 부분을 말할 때마다 하나씩 더하고, 장면이 바뀌어도 같은 물체를 이어 씁니다 (꿀벌: 벌이 장면 1에서 3까지 계속 춤을 춤. 문어: 몸속에 장기가 하나씩 등장).
- **마지막 줄(핵심 요약 한 줄)은 낭독이 끝나기 전에 띄워서 2초 이상 유지**합니다 (꿀벌: `recap`을 5b 시작 +1.6초에 띄워 엔딩 전환까지 약 2초 유지).
- 브랜드 끝 카드는 쓰지 않습니다 (찰스 엔딩이 그 역할).

### 6-3. 디자인

- 어두운 바탕 + **주제 색 1개** + 크림색 글자. 꿀벌: 바탕 `#120d08`, 꿀색 `#F6B21A`/`#FFB81C`, 글자 `#FFF4D6`. 문어: 바탕 `#07121b`, 코랄 `#F26B5B`, 글자 `#EAF4F8`.
- 글자는 **28px 이상**. 라벨은 둥근 알약(배경 반투명 어둡게)으로 가독성을 확보.
- 그림은 **코드로 그립니다 (SVG/CSS)**. 외부 이미지, 로고, 상표 캐릭터, 실존 인물 사진은 쓰지 않습니다 (예외: 사용자가 만든 찰스 그림 `assets/charles/`). 무작위(`Math.random`, `Date.now`)도 쓰지 않습니다 (같은 입력은 같은 영상이어야 한다).
- 상단 좌측에 **주제 칩**(작은 아이콘 + 주제 이름)이 본편 동안 유지됩니다 (`chip.html`, 시작은 `OFF + 2.3`).

### 6-4. 기술 규칙 (시험하며 확인한 것)

1. 장면 안에서는 `fromTo`를 씁니다. **같은 대상에 `fromTo`를 여러 번 쓰면 `immediateRender: false`**를 주고, 쉬는 상태는 `tl.set(…, 0)`으로 만듭니다 (아니면 마지막 `from` 값이 처음부터 적용됨).
2. 점선을 그려 넣을 때는 **마스크 경로**를 씁니다 (점선 자체의 `strokeDashoffset`을 움직이면 점이 흘러갈 뿐). 예시의 `dashed(pid, d, color, width)` 함수를 그대로 씁니다. 실선은 `pathLength="100" stroke-dasharray="100 120"`, `strokeDashoffset 100→0`.
3. 길을 따라 움직이는 물체는 CSS `offset-path: path(...)` + `offsetDistance` 트윈. 반복은 **유한 횟수**로 (`repeat: -1` 금지). `onUpdate` 콜백으로 화면을 바꾸지 않습니다.
4. **위치를 정하지 않은 `position:absolute` 0×0 요소는 화면 왼쪽 위(0,0)에 작은 점으로 남습니다.** 움직이는 물체 안의 부품은 그 물체의 자식으로 넣어 함께 움직이게 합니다.
5. 파이썬 문자열 서식(`%`)과 CSS의 `%`(`offset-distance: 0%`)가 충돌합니다. 자리표시자는 `__이름__` + `.replace()`로, 또는 `%%`로 씁니다.
6. 렌더 때 **CDN을 쓰지 않습니다.** 글꼴은 각 장면 파일 안에서 `@font-face`로 로컬 woff2를 씁니다.
7. 호스트 id = 장면 root의 `data-composition-id` = `window.__timelines` 키가 **같아야** 합니다. 장면 타임라인은 `gsap.timeline({ paused: true })`로 만들고 `window.__timelines["<id>"] = tl`로 등록합니다.
8. 모든 시간 요소에 `data-start`와 길이가 있어야 합니다. 자막은 절마다 `class="clip"` 호스트(`data-start`, `data-duration`).
9. 파이프라인에서 쓰지 않는 것: `Math.random()`, `Date.now()`, 네트워크 요청.

### 6-5. 자주 하는 실수 (꼭 피할 것)

| 실수 | 결과 |
|---|---|
| **본편에도 오리를 계속 보여 줌 (오리가 설명하는 영상)** | **실패.** 오리는 오프닝·엔딩 전용. `check_frames.py`가 불합격으로 알려 줌 |
| 6단계를 건너뛰고 오프닝·엔딩 장면만으로 영상을 만듦 | 위와 같음. `scene_kit.py`는 장면이 3개 미만이면 만들지 않음 |
| 속도를 1.2보다 올림 | "찰스입니다"의 "입니다"가 안 들림 |
| 오프닝 낭독문을 "찰스입니다!"로 바꿈 | 같은 이유 |
| 본편이 160자를 넘김 | 45초 초과, `build_track.py` 실패 |
| `snapshot`을 보지 않고 렌더 | 겹침·잘림·점이 그대로 영상에 남음 |
| 본편 장면 시각에 `OFF`를 이미 더한 값을 씀 | 모든 그림이 4.9초 늦게 나옴 |
| 호스트 `data-start`에 `OFF`를 더하지 않음 | 장면이 오프닝과 겹쳐 나옴 |
| 마지막 줄을 늦게 띄움 | 엔딩 전환 때 사라짐 (2초 유지 규칙 위반) |
| 한글이 들어간 폴더에서 작업 | 도구가 경로를 못 읽을 수 있음 (slug는 영문) |
| 같은 이름의 영상을 덮어씀 | 기존 결과 손실 (`-2` 붙이기) |
| 들어 보지 못했는데 "소리가 좋다"고 보고 | **거짓 보고.** 측정한 것과 못 들은 것을 구분 |

---

## 7. 예시 `build_video.py` 읽는 법 (새 주제로 바꿀 곳)

`examples/bee-waggle-dance/build_video.py` (약 590줄)의 구성입니다.

| 부분 | 하는 일 | 새 주제에서 |
|---|---|---|
| 맨 위: 입력 읽기, `OFF`, `T`, `ES` | 시각표 불러오기 | **그대로** |
| `D_START … OUT_DUR` | 장면 경계 계산 | 장면 수·경계에 맞게 **바꿈** |
| `FONTS`, `scene()` | 장면 HTML 틀, `T0`, `g()` | **그대로** |
| `dashed()`, `bee()`, `sun()`, `flower()` 등 | 그림 부품 (SVG) | **새 주제의 그림으로 바꿈** |
| `COMMON_JS` | 날갯짓(`flaps`), 흔들기(`wiggle`), 8자 이동(`dancePass`) | 주제에 맞는 동작 함수로 바꿈 (문어는 박동 `beats`, 흐름 `flow`) |
| 장면별 `*_css`, `*_body`, `*_js` | 장면 그림과 등장 타이밍 | **주제의 핵심.** 새로 씀 |
| `captions` 블록 | 절마다 자막 | **그대로** (색만 어울리게) |
| `bg` | 어두운 배경 + 천천히 움직이는 무늬 | 주제 색·무늬로 바꿈 |
| `charles.write_scenes(...)` | 오프닝·엔딩 | **그대로** |
| `chip` | 상단 주제 칩 | 아이콘·이름 바꿈 |
| `index.html` 조립 | 호스트 배치, 오디오 | 장면 수에 맞게 **바꿈** |

**새 주제 장면 만들기 순서**
1. `clauses.json`의 5줄을 **그림 한 장면씩**으로 옮깁니다. 각 줄에 "이 줄이 말하는 동안 화면에 무엇이 새로 나타나는가"를 한 문장씩 적습니다 (예: 3a → 직선 구간이 밝아짐, 3b → 각도 표시와 바깥 풍경 패널, 3c → 위쪽 = 태양).
2. 등장 시각을 **절 시작 시각 + 작은 값**으로 씁니다 (하드코딩한 초 단위 숫자를 쓰지 않는다. 대본이 바뀌어도 맞도록).
3. 처음에는 정지 그림 몇 장으로 시작해 `snapshot`으로 배치를 확인한 뒤 움직임을 더합니다.
4. 움직임(반복 동작)의 끝은 **장면이 끝나는 시각에 맞춥니다** (예: `dance_passes()`가 구간을 정수 번으로 나눠 마지막 동작이 장면 끝에 딱 끝남).

---

## 8. 막힐 때

| 증상 | 해결 |
|---|---|
| `setup_tts.py`가 SHA-256 불일치로 중단 | 릴리스 파일이 바뀐 것. 내용을 확인하고 사용자 승인 뒤 해시를 갱신 |
| 음성이 비어 있다 / `no frontend available` | sherpa-onnx가 1.13.8이 아닌 경우. `setup_tts.py`를 다시 실행 |
| `ModuleNotFoundError: sherpa_onnx` | 시스템 파이썬으로 실행함. **`PY`(가상환경 파이썬)로 `-I`를 붙여** 실행 |
| `build_track.py` "낭독이 N초" 실패 | 본편을 줄인다 (125~160자) |
| `scene_kit.py`: `spec 오류: scenes 는 3~5개여야 합니다` | 본편 그림 장면이 3개 미만이거나 5개 초과. 5줄을 장면 3~5개로 나눈다 |
| `scene_kit.py`: `장면들의 clauses 를 이어 붙이면 …` | 어떤 절이 빠졌거나 순서가 바뀜. 출력에 나온 본편 절 id 목록과 맞춘다 |
| `scene_kit.py`: `icon '…' 은 없습니다` | 아이콘 이름 오타. 목록은 6장 단계 6 또는 `scene_kit.py` 머리말 |
| `scene_kit.py`: `주의: 요약 한 줄이 …초만 보입니다` | `recap_at`/`recap_dt`를 앞으로 당긴다 (2초 이상 보여야 함) |
| `check_frames.py`가 본편에 오리가 많다고 불합격 | 6단계를 건너뛰었거나 본편에 찰스 그림을 넣음. 방법 A로 본편 장면을 만든다 |
| `check`에 `connector_orphan` 경고 | 마스크로 가린 점선이면 거짓 경보. snapshot에서 화면이 정상인지 확인 |
| 렌더가 `sub_timeline_script_failure` | GSAP CDN 사용. `assets/gsap.min.js`로 |
| 자막이 서로 겹침 | `build_track.py`의 `--intra`, `--inter`를 늘리거나 자막 클립 길이를 줄임 |
| 장면이 전체적으로 늦게/일찍 나옴 | 6-1의 `OFF` 규칙 위반 |
| 화면 왼쪽 위에 작은 점 | 6-4의 4번 |
| `ffmpeg`/`npm`이 없다 | 사용자에게 알려 설치하게 함 (`winget install Gyan.FFmpeg`) |
| Windows에서 `.sh`가 `pipefail` 오류 | 줄바꿈이 CRLF. `git clone -c core.autocrlf=false`로 받거나 `sed -i 's/\r$//'` |
| `python3`이 없다 | Windows는 `python` 또는 `py -3` |
| 한글 경로에서 오류 | 작업 폴더·slug를 영문으로 |

---

## 9. 재현 시험: 「꿀벌의 8자 춤」 (자비스가 제대로 하는지 확인)

**같은 대본으로 영상을 만들어 보고, 아래 기준과 비교합니다.** 합성에는 무작위가 있어 숫자는 조금씩 다릅니다. 시험은 두 가지입니다. **9-1(설계표 방식)을 먼저** 하고, 통과하면 9-2는 선택입니다.

### 9-1. 설계표 방식 (권장 경로) — `spec.json` + `scene_kit.py`

작업: `examples/bee-waggle-dance/`의 `clauses.json`과 `spec.json`을 새 작업 폴더(`knowledge-videos/bee-test/`)에 복사하고 **4~9단계**를 그대로 합니다 (3단계 대본과 6단계 설계표는 이미 있으니 `scene_kit.py`만 실행). 6단계 명령: `python <키트>/tools/scene_kit.py proj spec.json "$S"`.

| 항목 | Claude의 결과 | 허용 범위 |
|---|---|---|
| 본편 낭독 글자 수 | 143자 | 같아야 함 |
| 절 19개 | 오프닝 2 + 본편 12 + 엔딩 5 | 같아야 함 |
| `scene_kit.py` 출력 | 장면 5개 (question, flow, points, compare, stat) | 같아야 함 |
| `duration.txt` | 43 | 41~45 |
| `check` | 오류 0, 경고 0, 정보 1~2 | 오류 0 |
| 렌더 결과 | 1920×1080, 30fps, 약 12MB | |
| 영상 소리 크기 | −14.8 LUFS | −17~−13 |
| `verify_video.py` | 전부 PASS | 전부 PASS |
| **`check_frames.py`** | **전부 PASS** (오리 0/51장, 바뀌는 표본 28/51장, 최장 정지 1.5초) | 전부 PASS |
| 화면 | 오프닝(찰스) → 본편: ① "벌집 속은 깜깜합니다" 큰 제목과 ? 배지 → ② 별·벌·8 노드가 점선 화살표로 이어짐 → ③ 카드 3장 → ④ 막대 두 개 비교 → ⑤ 카를 폰 프리슈, 1973, 요약 한 줄 → 찰스 엔딩 | 같은 순서, **본편에 오리 없음** |

### 9-2. 직접 코드 방식 (선택) — `build_video.py`

`examples/bee-waggle-dance/build_video.py`를 복사해 `python build_video.py proj "$S"`로 6단계를 합니다.

| 항목 | Claude의 결과 | 허용 범위 |
|---|---|---|
| `check` | 오류 0, 경고 1, 정보 3~4 | 오류 0 |
| 렌더 결과 | 약 20~21MB, −14.7~−14.8 LUFS | −17~−13 |
| `check_frames.py` | 전부 PASS (오리 0/51장, 바뀌는 표본 40/51장) | 전부 PASS |
| 화면 | 오프닝 → 어두운 벌집과 제목 → 꿀벌과 꽃과 "?" → 벽면 8자 춤 → 각도 패널과 태양 → 막대 두 개 → 카를 폰 프리슈, 1973 → 찰스 엔딩 | 같은 순서 |

두 방식 모두 렌더 시간은 약 3.5~5분(GPU 없음)입니다. 기준에서 벗어나면 (1) 어느 단계에서 달라졌는지, (2) 오류 메시지 전문을 그대로 사용자에게 알립니다.

---

## 10. 보고 형식 (끝나면 이대로)

```
주제: / 길이: / 파일: (경로)
확인한 것 (직접 실행해 확인):
  - 점검: check 오류 N, 경고 N (이유)
  - 측정: verify_video.py 결과 (소리 크기, 길이, 해상도, 절마다 소리)
  - 화면: snapshot과 렌더한 MP4 프레임을 눈으로 확인한 시점들, 고친 것
  - 본편 장면: 방법 A/B, 장면 구성(종류와 절), check_frames.py 결과 (오리 N/M장, 바뀌는 표본, 최장 정지)
  - 음성: 절마다 6번 합성해 받아쓰기로 고름 (오류 글자 처음 → 고른 뒤) / 또는 1번만 합성
확인하지 못한 것:
  - 발음·억양·듣기 좋은지 (소리를 들을 수 없음)
  - 출처 중 원문을 직접 읽지 못하고 검색 요약만 본 것
  - Windows에서의 동작 (해당 시)
출처: (SOURCES.md 요약)
다음: 사용자가 들어 보고 알려 주면 수정. 올리려면 "유튜브·인스타 업로드"라고 말하면 jarvis-publish 절차(올릴 내용 보여 주고 확인)를 따름.
```

---

## 11. 정직한 한계

- **소리를 들을 수 없습니다.** 어떤 말이 자연스러운지, 이름 "찰스"의 발음이 좋은지는 사용자가 판단합니다. 보고에서 "소리가 좋다"고 쓰지 않습니다.
- 이 방법은 **Claude가 리눅스 클라우드 컨테이너에서** 두 편으로 확인한 것입니다. **Windows에서는 시험하지 못했습니다.** (PyPI에 Windows용 설치 파일이 있는 것만 확인.) 막히면 오류를 그대로 보고하세요.
- 본편 125~160자 규칙은 두 편(143자, 158자)과 일부 줄로 잰 값입니다.
- **방법 A(설계표)의 그림은 아이콘·도형·글자로 된 틀**입니다. 주제 맞춤 움직임(벌이 날아다니는 것 등)은 방법 B로 직접 코드를 써야 하고, 그것은 훨씬 어렵습니다. `scene_kit.py`는 꿀벌·문어 두 주제로만 시험했습니다 (둘 다 처음부터 렌더해 `verify_video.py`와 `check_frames.py` 전부 PASS: 꿀벌 43.0초·−14.8 LUFS·12.3MB, 문어 44.0초·−14.8 LUFS·12.0MB).
- `check_frames.py`는 "본편에 그림이 있고 움직이는가"만 잽니다. 그림이 보기 좋은지는 snapshot을 눈으로 봐야 합니다.
- 한국어 음성인식 모델이 이름 "찰스"를 자주 "철수·체스"로 틀려서, 이름 발음은 측정하지 못했습니다.
- 사실 확인은 웹 검색 요약 기준입니다. 의학·법률·투자 주제는 하지 않습니다.
- 파이프라인은 **외부 서비스를 쓰지 않습니다** (음성·그림 모두 내 컴퓨터). 클라우드 음성(타입캐스트 등)이나 HeyGen 서비스를 쓰려면 먼저 사용자 동의가 필요하고, 이 문서는 그 연결을 갖고 있지 않습니다.
