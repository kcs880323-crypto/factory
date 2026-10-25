# 이 저장소에서 Claude가 따를 것

- 사용자가 **"지식영상 생성"**이라고 말하면 `jarvis-skills/skills/jarvis-knowledge-video/SKILL.md`를 읽고 그 절차대로 지식 공유 영상(한국어 나레이션 + 자막, 찰스의 고정 오프닝·엔딩 포함 35~45초)을 만든다.
  - 오프닝 "30초만 주면, 지식 하나 드릴게요. 찰스입니다!" / 엔딩 "알아두면 언젠가 쓴다! 오늘의 지식공유가 유익했다면 좋아요와 구독, 찰스였습니다!"는 사용자가 정한 고정 문구이고 캐릭터 찰스는 사용자가 만든 오리 박사 그림이고 `jarvis-skills/skills/jarvis-knowledge-video/assets/charles/`에 있다 (사용자가 새 그림을 주면 그때만 교체). 문구와 그림은 바꾸지 않는다.
  - 나레이션 목소리는 기본 Supertonic 3의 목소리 5, 속도 1.2, `natural`로 만든다 (목소리는 사용자가 샘플 8개를 듣고 고른 것. 짧은 절은 자동으로 더 느리게 합성하고 클립 앞뒤 무음은 잘라 낸다). 본편 낭독은 공백·문장부호 제외 약 125~160자로 써야 45초 안에 들어간다. 오프닝의 낭독문 "찰스입니다."의 마침표는 의도된 것이다 (느낌표가 붙으면 끝말이 삼켜진다. 자막은 "!" 그대로). 사용자가 목소리·속도·톤을 바꿔 달라고 하면 SKILL.md의 "목소리 (조사 → 측정 → 사용자가 듣고 고른 것)"를 따른다.
  - 주제를 말하지 않으면 `knowledge-videos/ledger.json`에 없는 주제를 무작위로 고른다.
  - 처음 한 번(`~/.jarvis-knowledge-video`가 없을 때)은 SKILL.md 0번의 알림과 동의를 거친다.
  - 영상은 HyperFrames 플러그인으로 만든다 (`jarvis-skills/skills/jarvis-hyperframes/SKILL.md`). 설치되어 있지 않으면 `bash jarvis-skills/install.sh --with-video-skills`를 안내한다.
  - 만든 뒤 `knowledge-videos/ledger.json`에 기록한다. MP4 파일은 저장소에 올리지 않는다 (사용자가 요청하지 않는 한).
- 예약, 가격 비교, 일정·메일, 브리핑, 문서·회의 요약 같은 **비서 업무 요청**은 `jarvis-skills/skills/jarvis-secretary/SKILL.md`의 절차를 따른다 (제안형 대화, 위임 한도 판정 `secretary_store.py decide`, 결제·민감정보는 하지 않음). "이거 배워", "다음부터 이렇게 해줘"는 `jarvis-skills/skills/jarvis-skill-learner/SKILL.md`를 따른다.
