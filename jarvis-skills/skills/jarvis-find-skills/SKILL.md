---
name: jarvis-find-skills
description: "Use before building a capability from scratch: search the public agent-skill ecosystem (skills.sh, `npx skills find`) for an existing skill, compare candidates, and install one only with the user's approval. 만들고 싶은 작업에 쓸 만한 스킬이 이미 있는지 찾고 추천·설치."
---

# find-skills: 직접 만들기 전에 이미 있는 스킬부터 찾기

어떤 스킬이 있는지 몰라서 처음부터 다 만드는 상황을 막는 스킬입니다.
공개된 스킬 목록(skills.sh)을 뒤져 알맞은 것을 추천하고, 사용자가 승인하면 설치까지 합니다.

## 언제 쓰나

- "~하고 싶다", "~하는 스킬 있어?"처럼 특정 작업을 반복해서 쓰고 싶어 할 때
- 전문 분야(디자인, 테스트, 배포 등)의 작업을 시작하는데 마땅한 스킬이 없을 때
- 새 스킬을 직접 쓰기 전

## 1. 준비 확인

```bash
node --version                         # v22.20 이상
ls .claude/skills ~/.claude/skills     # find-skills 가 이미 있는지
```

없으면 설치한다 (프로젝트 폴더 기준. 계정 전체는 `-g` 추가).

```bash
npx --yes skills@latest add vercel-labs/skills --skill find-skills --agent claude-code --yes --copy
```

성공하면 `✓ find-skills (copied)` 줄이 나온다.

## 2. 찾는 순서

1. **무엇이 필요한지 정리한다.** 분야(예: React, 테스트)와 구체적인 작업(예: 테스트 작성)을 나눈다.
2. **먼저 <https://skills.sh> 목록을 본다.** 설치 수 순으로 정렬되어 있어 검증된 후보가 먼저 보인다.
3. **명령으로 검색한다.** 키워드는 구체적일수록 좋다. 안 나오면 비슷한 말로 바꿔 본다.
   ```bash
   npx skills find react testing
   npx skills find pr review --owner vercel-labs   # 만든 곳을 좁힐 때
   ```
4. **후보를 검증한다.** 검색 결과만 보고 추천하지 않는다.
   - 설치 수 (1K 이상 선호, 100 미만은 신중하게)
   - 만든 곳 (공식 저장소인지)
   - 원본 저장소의 상태와 최근 갱신
5. **사용자에게 후보를 보여 주고 승인을 받는다.** 후보마다 이름과 하는 일, 설치 수와 만든 곳, 설치 명령, skills.sh 링크를 적는다.
6. **승인된 것만 설치한다.**
   ```bash
   npx --yes skills@latest add <owner/repo> --skill <skill-name> --agent claude-code --yes --copy
   ```

## 3. 관련 명령

| 명령 | 하는 일 |
|---|---|
| `npx skills find [검색어]` | 스킬 검색 |
| `npx skills list` | 설치된 스킬 목록 |
| `npx skills check` | 갱신 가능한 스킬 확인 |
| `npx skills update [이름...]` | 갱신 |
| `npx skills remove [이름...]` | 삭제 |
| `npx skills use <출처>@<스킬>` | 설치 없이 한 번만 사용 |
| `npx skills init [이름]` | 새 스킬 템플릿 만들기 |

## 지켜야 할 것

- **설치 전에 반드시 사용자 승인을 받는다.** 스킬은 Claude에게 주는 지침이라 설치하면 Claude의 행동이 바뀐다. 설치 도구도 "설치 전 스킬을 검토하라, 스킬은 전체 권한으로 실행된다"고 안내한다.
- 저장소는 `owner/repo` 형태로 통째로 적고, 여러 스킬이 든 저장소는 `--skill`로 하나만 고른다. 옵션 없이 설치하면 비슷한 스킬이 한꺼번에 들어온다.
- 이미 설치된 스킬은 다시 설치하지 않는다. 기존 파일을 덮어쓰지 않는다.
- 설치 도구는 익명 사용 통계를 수집한다. 끄려면 `DISABLE_TELEMETRY=1` 또는 `DO_NOT_TRACK=1` 환경변수를 설정한다.

## 결과가 없을 때

- 알맞은 스킬이 없다고 솔직히 말한다. 검색에 실패한 것(네트워크, 접속 차단)과 결과가 정말 없는 것을 구분해서 보고한다.
- 샌드박스처럼 `skills.sh` 접속이 막힌 환경에서는 `npx skills find`가 오류 없이 "No skills found"만 출력할 수 있다. 이 경우 검색 결과가 없는 것으로 단정하지 않는다.
- 직접 돕겠다고 제안하고, 자주 쓸 작업이면 `npx skills init <이름>`으로 직접 스킬 만들기를 제안한다.

공식 자료: <https://skills.sh> · <https://github.com/vercel-labs/skills>
