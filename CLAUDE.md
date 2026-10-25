# 이 저장소에서 Claude가 따를 것

- 사용자가 **"지식영상 생성"**이라고 말하면 `jarvis-skills/skills/jarvis-knowledge-video/SKILL.md`를 읽고 그 절차대로 30~40초 지식 공유 영상(한국어 나레이션 + 자막)을 만든다.
  - 주제를 말하지 않으면 `knowledge-videos/ledger.json`에 없는 주제를 무작위로 고른다.
  - 처음 한 번(`~/.jarvis-knowledge-video`가 없을 때)은 SKILL.md 0번의 알림과 동의를 거친다.
  - 영상은 HyperFrames 플러그인으로 만든다 (`jarvis-skills/skills/jarvis-hyperframes/SKILL.md`). 설치되어 있지 않으면 `bash jarvis-skills/install.sh --with-video-skills`를 안내한다.
  - 만든 뒤 `knowledge-videos/ledger.json`에 기록한다. MP4 파일은 저장소에 올리지 않는다 (사용자가 요청하지 않는 한).
