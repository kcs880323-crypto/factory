# 현재 영상 퀄리티 1차 정밀 진단서 (v1)

- **진단일:** 2026-11-23
- **작성자:** Antigravity (영상 퀄리티 R&D)
- **진단 근거:** 저장소 코드 및 문서 정밀 분석
  - 스킬·스크립트: `jarvis-skills/skills/jarvis-knowledge-video/` (`SKILL.md`, `charles.py`, `build_track.py`, `tts_clauses.py`, `verify_video.py`), `jarvis-publish/SKILL.md`, `jarvis-hyperframes/SKILL.md`, `jarvis-lemo-opuscar/SKILL.md`, `jarvis-onetake/SKILL.md`, `jarvis-collage-broll/SKILL.md`, `jarvis-claude-animation-base/SKILL.md`
  - 양산 도구·예시: `knowledge-videos/MANUAL.md`, `knowledge-videos/tools/scene_kit.py`, `check_frames.py`, `bee-waggle-dance/`, `octopus-three-hearts/`, `ledger.json`

> **정직한 진단 원칙 (확인한 것 vs 확인하지 못한 것)**
> - **직접 확인함:** 코드·설계표·레이아웃 좌표·오디오 파이프라인·검수 기준·문서상 실측 기록(LUFS, CER, 렌더 시간, 프레임 변화율).
> - **확인하지 못함:** 실제 렌더된 MP4 파일(저장소에 미포함)의 시청각 체감, Supertonic 3 목소리 5의 실제 발음·억양 자연스러움, 유튜브/인스타 실전 게시 후 조회수·시청 지속률(Retention) 데이터.

---

## 1. 현재 파이프라인의 강점 (반드시 유지해야 할 자산)

1. **높은 재현성과 결정론적(Deterministic) 렌더링:** HTML + GSAP + HyperFrames 기반으로 `Math.random()`·CDN을 배제해 같은 입력이면 항상 같은 영상이 나옵니다.
2. **목소리 중심(Voice-as-Clock) 타이밍 설계:** `clauses_timing.json`과 `OFF` 오프셋 공식으로 대본·오프닝 길이가 바뀌어도 본편 애니메이션 싱크가 무너지지 않습니다.
3. **강력한 자동 품질 게이트:** `check`(HyperFrames 규칙 점검), `verify_video.py`(해상도·fps·LUFS·절별 RMS), `check_frames.py`(본편 오리 노출 방지·정지화면 방지)까지 엔지니어링 품질 관리가 이미 탄탄합니다.
4. **사실 검증 문화:** `SOURCES.md` 기반 팩트체크와 과장 금지 `publish.json` 규칙이 잘 잡혀 있습니다.
5. **확장 가능한 영상 도구 생태계:** HyperFrames 외에도 Lemo-Opuscar(코드 기반 59+ 화풍), onetake(비상업 전용 모션 레퍼런스 분석), Collage B-roll(종이 콜라주), Claude Animation Base(p5.brush 손그림) 스킬 안내서가 이미 정비되어 있습니다.

---

## 2. 영역별 1차 퀄리티 진단 (7대 축)

| 영역 | 현재 수준 (코드 기준) | 핵심 병목 / 약점 | 우선순위 | 개선 방향 (R&D 가설) |
|---|---|---|---|---|
| **1. 화면비 & 플랫폼 적합성** | 1920×1080 (16:9 가로) 고정. 릴스 업로드 시 `publish_tool.py`가 흐린 배경 위 가운데 띠(레터박스)로 기계적 변환 | 숏폼(릴스·쇼츠) 모바일 화면에서 본편 그림이 작게 보이고 "재활용 영상" 느낌 발생 | **P0** | **9:16 세로 네이티브(1080×1920)** 레이아웃 엔진 구축 (또는 16:9 / 9:16 듀얼 출력) |
| **2. 사운드 디자인 (BGM · SFX)** | 모노 나레이션 1트랙(`narration.wav`, −16 LUFS)만 존재. BGM·효과음 0개 | 장면 전환·전구 반짝임·좋아요/구독 클릭이 전부 무음이라 체감 완성도·몰입감이 크게 저하됨 | **P0** | 로컬 로열티프리 BGM(사이드체인/덕킹 −28~−24 LUFS) + 타이밍 연동 SFX(pop/whoosh/chime/click) 자동 믹싱 |
| **3. 훅(Hook) & 리텐션 구조** | 첫 ~4초가 고정 오프닝("30초만 주면… 찰스입니다!"), 그 뒤 본편 5줄(~26초), 엔딩(~8초) | 숏폼 이탈이 집중되는 첫 1.5~3초에 주제 호기심 유발 요소가 없음. 엔딩 8초도 쇼츠 기준 다소 길 수 있음 | **P1** | 오프닝 상단 주제 티저 동시 노출 또는 "2초 질문 훅 → 압축 오프닝 → 본편" A/B 테스트 (대표·클로드 협의 필수) |
| **4. 본편 비주얼 & 모션 깊이** | **방법 A(`scene_kit.py`):** 5종 템플릿(`question/flow/points/compare/stat`) + 단순 SVG 아이콘 28종<br>**방법 B(`build_video.py`):** 편당 ~600줄 수작업 코딩 | 방법 A는 빠르지만 시각적 다양성 한계(PPT 느낌), 방법 B는 고퀄리티지만 양산 확장성 낮음 | **P1** | **방법 A+ (하이브리드 모듈 키트):** 모션 템플릿 10~15종 확장(타임라인, 줌인 다이어그램, 게이지, 전후 비교, 지도/경로 등) + 장면별 커스텀 SVG 슬롯 + 배경 파티클/깊이감 강화 |
| **5. 자막 디자인 & 리듬** | Pretendard 700, 56px, 화면 하단(`top: 884px`) 절 단위 정적 교체 | 숏폼 시청자의 시선을 잡는 **키워드 강조(색상/스케일)**, 단어/어절 단위 팝인, 안전 영역(Safe Zone: UI 버튼 회피) 미적용 | **P1** | 9:16 플랫폼 Safe Zone 반영 + 핵심어 컬러 하이라이트(`<em>` 마크업) + 탄성(spring) 등장 모션 |
| **6. 캐릭터(찰스) 생동감** | 4개 정지 포즈 PNG + RMS 기반 몸 늘림/흔들림(`charles.py`). 입·눈 고정 | 오프닝/엔딩이 반복 노출되는 채널 시그니처인데 움직임이 다소 경직될 수 있음 | **P2** | CSS/SVG 레이어로 미세 호흡·눈 깜빡임·유리병 별 반짝임 추가. 장기적으로 입 벌림/닫힘 포즈 추가 검토 |
| **7. 나레이션(TTS) & 렌더 속도** | Supertonic 3 목소리 5(속도 1.2, 오프라인, OpenRAIL-M). CER ~20%, 6회 합성+ASR 선별 시 ~2.5분. 자비스 PC 렌더 12~19분 보고 | ① 로컬 TTS의 억양/감정 표현 한계 + OpenRAIL-M 상업 제한 원문 미확인<br>② 렌더 속도 편차 및 자비스 PC 병목 미해결 | **P2** | ① 발음 사전(전처리)·절 호흡 튜닝 + 필요 시 상용 클라우드 TTS A/B 비교<br>② `DESKTOP-4CMADIK` 하드웨어 사양 기준 렌더 벤치마크 및 경량화 |

---

## 3. 기존 영상 제작 스킬 5종 활용 가능성 평가

저장소 `jarvis-skills/skills/`에 이미 정리된 외부 영상 스킬 5종의 R&D 활용 가능성은 다음과 같습니다.

| 스킬 | 상업 이용 | 현재 R&D 활용도 | 판단 |
|---|---|---|---|
| **HyperFrames** (`jarvis-hyperframes`) | ✅ Apache-2.0 | ★★★★★ (현재 핵심 엔진) | **메인 엔진 유지.** 세로 포맷·BGM/SFX·모션 키트 고도화의 중심 |
| **Lemo-Opuscar** (`jarvis-lemo-opuscar`) | ✅ MIT (결과물 본인 소유) | ★★★☆☆ (비주얼 레퍼런스/스페셜 편) | 59+ 코드 화풍(수채화, 다큐, 분해도 등)에서 **연출 기법·배경 텍스처·카메라 워크**를 벤치마킹해 `scene_kit` 고도화에 흡수 |
| **Collage B-roll** (`jarvis-collage-broll`) | ⚠️ MaxFusion 유료·외부 전송 | ★★☆☆☆ (특수 컷 B-roll) | 외부 유료 MCP 의존. 필요 시 HyperFrames 내에서 **로컬 SVG/CSS 종이 콜라주 스타일**로 자체 구현하는 편이 비용·재현성 면에서 유리 |
| **Claude Animation Base** (`jarvis-claude-animation-base`) | ⚠️ MIT 코드 / Clawd 상표 불명확 | ★★☆☆☆ (연출 기법 참고) | p5.brush "끓는 선(boiling linework)" 같은 손그림 질감 아이디어만 참고 가능 (GPU 없으면 프레임당 55초로 실전 양산 부적합) |
| **onetake** (`jarvis-onetake`) | ❌ **PolyForm Noncommercial** | ★☆☆☆☆ (상업 채널 사용 불가) | 수익화/채널 운영 목적이면 **직접 사용 금지**. (비상업 연구용으로도 혼입 방지 위해 배제 권장) |

---

## 4. 권장 R&D 로드맵 (초안 — Claude Desktop 협의 후 확정)

- **Sprint 1 (체감 퀄리티 점프 — 가장 ROI 높은 2가지):**
  1. `EXP-001`: **9:16 세로 네이티브 템플릿 + 플랫폼 Safe Zone + 강조 자막** 프로토타입 (기존 「꿀벌의 8자 춤」 대본으로 A/B 비교)
  2. `EXP-002`: **BGM(덕킹) + 타이밍 연동 SFX 믹싱 파이프라인** (`build_track.py` 확장 또는 `mix_audio.py` 신설)
- **Sprint 2 (시각적 차별화 & 양산성):**
  3. `EXP-003`: **`scene_kit.py` v2 (방법 A+ 하이브리드)** — 신규 장면 타입(다이어그램 줌, 타임라인, 게이지/비율, 분할 비교) + 배경 질감/파티클 + 카메라 미세 패닝
  4. `EXP-004`: **오프닝 훅 A/B 구조 실험** (Claude Desktop 전략 확정안 반영)
- **Sprint 3 (캐릭터·음성·스킬 승격):**
  5. `EXP-005`: 찰스 마이크로 애니메이션(눈 깜빡임/별빛/호흡) 및 TTS 억양·전처리 개선
  6. 검증된 개선안을 `jarvis-skills/skills/jarvis-knowledge-video/`에 정식 스킬 v2로 패키징 → 자비스 PC 배포
