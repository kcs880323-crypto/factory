# EXP-NNN: <실험 제목>

- **날짜:** YYYY-MM-DD
- **담당:** Antigravity (영상 R&D)
- **트랙:** T1 / T2 / T3 / T4 / T5
- **연관 안건:** `AGENDA-NNN`
- **기준선(Baseline):** `BASE-A` 또는 `BASE-B`
- **상태:** 🔵 진행 중 / 🟡 평가 대기 / ✅ 합격 / ❌ 기각

---

## 1. 가설 (Hypothesis)
- **무엇을 바꾸면:** (변경 변인 1~2가지를 명확히 작성)
- **어떤 지표/체감이 개선될 것이다:** (기대 효과)

## 2. 변경 사항 (Changes)
- 수정/추가 파일:
  - `video-rnd/prototypes/...`
- 고정 유지한 것: (대본, 목소리, 고정 문구 등)

## 3. 정량 측정 결과 (Baseline vs Experiment)

| 항목 | Baseline | Experiment | 변화 | 합격 여부 |
|---|---|---|---|---|
| 해상도 / FPS | 1920×1080 / 30fps | | | |
| 영상 길이 (초) | | | | |
| 전체 음량 (LUFS) | −14.8 LUFS | | | (−17 ~ −13) |
| `check` 오류 / 경고 | 0 / 0 | | | (오류 0) |
| `verify_video.py` | ALL PASS | | | |
| `check_frames.py` 변화 표본 | 28/51장 | | | |
| 렌더 시간 (초, PC 환경 명시) | | | | |

## 4. 시각 점검 (Snapshot & MP4 프레임)
- 확인한 시점(초):
- 발견한 결함 및 수정 내역:
- 확인하지 못한 것(정직하게 기록): (예: 오디오 체감 밸런스 등)

## 5. 대표(사용자) 시청/청취 피드백 & Claude Desktop 의견
- **대표 피드백:**
- **Claude Desktop(마케팅/플랫폼) 코멘트:**

## 6. 결론 및 다음 액션
- **최종 판정:** ✅ 양산 승격 / 🔁 보완 재실험 / ❌ 기각
- **승격 대상 파일:** (승격 시 `jarvis-skills/` 또는 `knowledge-videos/tools/` 반영 내역)
