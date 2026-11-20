---
name: jarvis-collage-broll
description: "Use when the user wants a 5-second 9:16 paper-collage B-roll clip from one spoken sentence (halftone cut-outs, three approval gates, optional fitted voiceover) with the collage-broll-explainers skill, or asks to install or check it. Installs the pinned SKILL.md, and connects the third-party MaxFusion MCP (paid, account login) only after explicit consent. 한 문장을 5초 종이 콜라주 B-roll 영상으로 만드는 스킬 설치·사용 안내."
---

# Collage B-roll: 한 문장 → 5초 종이 콜라주 영상

말 한 문장(예: "눈덩이는 첫 한 번만 밀면 언덕이 알아서 해 준다")을 받아 **9:16, 5초짜리 종이 콜라주 설명 영상**으로 만드는 스킬입니다. 흑백 하프톤 컷아웃이 색 바탕 위에서 조각조각 조립되는 모습이고, 원하면 목소리(내레이션)를 길이에 맞춰 붙입니다.

**세 번의 승인 관문**이 핵심입니다: ① 시각적 은유(메타포)만 제안 → 사용자 승인 ② 스틸 이미지 생성 → 사용자 승인 ③ 마지막에 영상 생성. 승인 없이 다음 단계로 가지 않아서 비용이 드는 영상 생성이 잘못된 아이디어에 쓰이지 않게 합니다.

## 0. 먼저 알릴 것 (자료에서 빠졌거나 다르게 읽어야 하는 부분)

| 항목 | 내용 |
|---|---|
| **유료·외부 서비스** | 이미지·영상·음성 생성은 **전부 MaxFusion(maxfusion.ai) MCP**를 거친다. 가입·로그인이 필요한 제3자 서비스다. 요금 체계는 이 저장소에 없어서 **확인하지 못했다.** 사용자가 직접 요금을 확인해야 한다. |
| **내 자료가 외부로 나간다** | 문장, 만든 스틸, 시작·끝 프레임(`maxfusion_upload_asset`으로 업로드), 선택한 목소리와 대사가 MaxFusion으로 전달된다. 비공개 문구·사진·개인정보는 넣지 않는다. |
| **"공식 저장소"가 아니다** | 개인(MegaTroll222)이 만든 저장소다. 중국어 원본 `gbro-collage-broll`(pyang5166, Codex용)을 Claude Code + MaxFusion용으로 영어 번역·각색했다. 라이선스는 MIT. |
| **자료의 "자기 키로 쓰는 방법"은 그대로 안 된다** | 저장소는 Google AI Studio 키와 ElevenLabs 키로 바꿔 쓸 수 있다고 하지만, 그러려면 `SKILL.md` 안의 `maxfusion_*` 호출을 **직접 고쳐 써야 한다.** 설치만으로 되지 않는다. |
| **영상 모델이 고정** | 스킬은 영상 모델을 `gemini-omni-flash`로 고정하고 사용자에게 선택지를 주지 않는다. |
| **음성** | 목소리는 사용자의 MaxFusion 라이브러리에서 고른다. **사용할 권리가 있는 목소리만** 쓴다. |

## 1. 필요한 것

- `ffmpeg` (프레임 준비, 품질 검사, 오디오 합치기는 내 컴퓨터에서 실행)
- MaxFusion 계정과 MCP 연결 (아래 3번, 사용자 동의 후)

## 2. 스킬 설치 (커밋·해시 고정)

```bash
ls ~/.claude/skills/collage-broll-explainers/SKILL.md .claude/skills/collage-broll-explainers/SKILL.md   # 있으면 이미 설치됨
bash <경로>/jarvis-skills/install.sh --with-video-skills      # 계정 전체는 --global 추가
```

스크립트는 **시험한 커밋 `2e9b8a93…`의 `SKILL.md`(약 21KB) 한 파일**만 받고, 내려받은 내용의 **SHA-256이 `5272b49c…ac0124`와 같을 때만** 설치한다. 다르면 설치하지 않고 실패로 보고한다 (저장소가 바뀐 것이므로 내용을 확인해야 한다). 자료의 `main` 주소를 그대로 쓰지 않는다.

```bash
# 수동 (받은 뒤 해시를 확인하세요)
mkdir -p ~/.claude/skills/collage-broll-explainers
curl -fsSL -o ~/.claude/skills/collage-broll-explainers/SKILL.md \
  https://raw.githubusercontent.com/MegaTroll222/VOX-COLLAGE-BROLL/2e9b8a93a2834e4d670700ab9e4827a8baf3b668/SKILL.md
sha256sum ~/.claude/skills/collage-broll-explainers/SKILL.md     # 5272b49c6581b167…ac0124 이어야 함
```

설치 후 Claude Code를 완전히 종료했다가 다시 켠다.

## 3. MaxFusion 연결 (사용자 동의 후에만, 설치 스크립트가 하지 않음)

`install.sh`는 MCP를 **등록하지 않는다.** 위 0번을 사용자에게 설명하고 동의를 받은 뒤:

```bash
claude mcp add --transport http maxfusion https://mcp.maxfusion.ai/mcp
claude mcp list          # maxfusion 이 보이는지 확인
```

첫 사용 때 MaxFusion 계정 인증을 요구한다. **로그인은 사용자가 직접 한다.** 자비스는 계정 정보를 묻거나 받지 않는다. 연결하지 않았으면 스킬은 "MCP가 연결되지 않았다"며 멈추게 되어 있다.

## 4. 사용

```text
콜라주 b-roll 만들어 줘: "<내 문장 한 줄>"
```

스킬이 다음 순서로 진행한다. **각 관문에서 멈추고 사용자 답을 기다린다.**

1. 시각 은유 후보 제안 (글로만) → 선택
2. 스틸 이미지 생성 → 확인
3. 시작 프레임(빈 색 바탕)과 끝 프레임을 로컬 ffmpeg로 준비해 업로드 → 영상 생성
4. 프레임별 자체 점검(컨택트 시트, 첫 프레임 순도, 끝 프레임 비교, 가짜 글자 검사)
5. (선택) 목소리를 고르고, 대사를 만들어 4~5초에 맞춰 영상에 합치기

생성 비용이 드는 호출(이미지·영상·음성) 전에 **몇 번 호출할지** 사용자에게 알린다. 실패해서 다시 만들 때도 같다.

## 지켜야 할 것

- **세 관문을 건너뛰지 않는다.** 사용자가 "그냥 다 해 줘"라고 해도 비용이 드는 영상 생성 전에는 승인을 받는다.
- 동의 없이 MaxFusion에 연결하거나 자료를 올리지 않는다. 계정 정보, 토큰, 키를 묻거나 출력하지 않는다.
- 비공개·개인 정보·타인의 얼굴 사진을 입력으로 쓰지 않는다. 저작권이 있는 이미지를 소스로 쓰지 않는다.
- 영상에 글자가 필요하면 가짜 글자(깨진 문자)가 생기지 않았는지 확인하고, 문제면 사용자에게 알린다.
- 설치된 `SKILL.md`를 수정하지 않는다. 업데이트는 새 커밋의 내용을 사용자에게 보여 주고 해시를 바꾸는 것까지 승인받은 뒤에 한다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| 스킬이 MCP 연결을 요구하며 멈춤 | 3번의 동의·연결 절차 |
| MaxFusion 인증 창이 안 뜸 / 실패 | 사용자가 `/mcp`에서 인증. 네트워크가 `mcp.maxfusion.ai`를 막는지 확인 |
| 해시 불일치로 설치가 거부됨 | 저장소 내용이 바뀐 것. 새 내용을 읽고 사용자 승인 후 해시를 갱신 |
| ffmpeg 명령 실패 | ffmpeg 설치 확인 (윈도우: `winget install Gyan.FFmpeg`) |

시험한 범위: `SKILL.md`를 읽고 명령·주소·키 관련 문장을 검색해 MaxFusion 호출과 ffmpeg 명령 외에 외부 접속, 삭제, 키 요구가 없음을 확인했고, 고정 커밋의 내용과 해시를 확인했다. **MaxFusion 계정이 없어서 실제로 영상을 만들어 보지는 못했다.** 윈도우도 시험하지 못했다.

공식 자료: <https://github.com/MegaTroll222/VOX-COLLAGE-BROLL> · 원작 <https://github.com/pyang5166/gbro-collage-broll> · <https://maxfusion.ai>
