# video-rnd — 영상 퀄리티 R&D · 스킬 개발 워크스페이스

이 폴더는 **Antigravity(영상 퀄리티 R&D)**가 양산 파이프라인(`knowledge-videos/`, `jarvis-skills/`)을 망가뜨리지 않고 새로운 영상 연출·사운드·레이아웃·스킬을 안전하게 실험하고 검증하기 위한 공간입니다.

## 폴더 구조

```text
video-rnd/
├── README.md                  ← R&D 운영 규칙 및 양산 승격 절차 (이 문서)
├── DIAGNOSIS_v1.md            ← 현재 영상 퀄리티 1차 정밀 진단서
├── EXPERIMENT_LOG.md          ← 전체 실험 마스터 인덱스 (EXP-001 ~ )
├── .gitignore                 ← R&D 중 생성되는 MP4/WAV/임시 렌더 폴더 커밋 방지
├── experiments/
│   ├── TEMPLATE.md            ← 새 실험 문서 표준 양식
│   └── EXP-NNN-<slug>.md      ← 개별 실험 기록 (가설 · 변경점 · 측정값 · 판정)
└── prototypes/                ← (실험 시작 시 생성) 실험용 스크립트·템플릿 코드
```

## 실험 진행 원칙 (4단계 사이클)

1. **가설 정의 (Hypothesis):** `SYNC_BOARD.md`에서 합의된 우선순위에 따라 `EXP-NNN` 번호를 발급하고 `experiments/EXP-NNN-<slug>.md`를 생성합니다.
2. **통제된 A/B 구현 (Build):** 기준선(Baseline: 「꿀벌의 8자 춤」 또는 「문어의 심장은 세 개」)과 **동일한 대본**으로 변경 변인 1~2개만 적용해 비교 가능하게 만듭니다.
3. **정량 + 정성 검증 (Verify):**
   - **정량:** `check`(오류 0), `verify_video.py`(해상도·fps·LUFS·RMS), `check_frames.py`(움직임/오리 노출), 렌더 시간(초/프레임).
   - **시각:** `snapshot` 컨택트 시트 + 렌더된 MP4 프레임 추출 확인.
   - **정성(대표 평가):** 실제 재생 시청·청취 후 합격/보류/기각 판정 (AI가 임의로 "좋다"고 판정하지 않음).
4. **양산 승격 (Promote):** 대표 승인 후 `jarvis-skills/skills/jarvis-knowledge-video/` 및 `knowledge-videos/tools/`에 반영하고, `SYNC_BOARD.md`의 현황판과 결정 로그를 갱신합니다. 이후 자비스(메인 PC)가 `git pull`로 새 스킬을 받아 실전 양산에 사용합니다.
