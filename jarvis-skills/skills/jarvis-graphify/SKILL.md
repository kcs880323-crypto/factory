---
name: jarvis-graphify
description: "Use to understand an unfamiliar codebase (or a folder of docs, PDFs, images) as a queryable knowledge graph: run /graphify, read graphify-out/GRAPH_REPORT.md, and use graphify query/path/explain/god-nodes. Installs Graphify (PyPI package graphifyy) and sets cost, privacy and CLAUDE.md rules. 처음 보는 프로젝트를 지도 한 장(지식 그래프)으로 파악."
---

# Graphify: 코드베이스를 질문할 수 있는 지도로 만들기

Graphify는 폴더 하나(코드, 문서, 논문 PDF, 이미지)를 읽어서 **지식 그래프**로 엮어 줍니다. 결과는 `graphify-out/` 폴더에 쌓입니다.

| 파일 | 내용 |
|---|---|
| `graph.html` | 브라우저로 여는 그래프 (노드 클릭, 검색, 묶음별 필터) |
| `GRAPH_REPORT.md` | 핵심 노드(god nodes), 뜻밖의 연결, 추천 질문 요약 |
| `graph.json` | 저장된 그래프. 나중에 다시 읽지 않고 질문할 수 있다 |
| `cache/` | 파일 해시 캐시. 다시 실행하면 바뀐 파일만 처리 |

모든 연결선(edge)에는 `EXTRACTED`(코드에서 직접 확인), `INFERRED`(추정), `AMBIGUOUS`(모호) 표시가 붙어서 **확인된 것과 추측을 구분**할 수 있습니다. 라이선스는 Apache-2.0입니다.

## 먼저 알아 둘 것

1. **패키지 이름은 `graphifyy`(y 두 개)다.** README는 `graphify` 이름을 되찾는 중이라 임시 이름이라고 설명한다. 명령과 스킬 이름은 그대로 `graphify`다. `pip install graphify`처럼 y 하나로 설치하지 않는다 (시험 시점에 PyPI에서 `graphify`는 조회되지 않았다).
2. **계정 전체에 영향을 준다.** `graphify install`은 `~/.claude/skills/graphify`에 스킬을 설치하고, **전역 지침 파일 `~/.claude/CLAUDE.md`에 `# graphify` 섹션을 추가**한다(없으면 만든다). 사용자가 `/graphify`를 입력하면 이 스킬을 먼저 쓰라는 내용이다. 기존 내용은 보존되고 두 번 실행해도 섹션은 하나만 남는 것을 확인했다. 설치 전에 사용자에게 알리고, 이 패키지의 스크립트는 `CLAUDE.md`를 먼저 백업한다.
3. **`graphify uninstall`은 이 `CLAUDE.md` 섹션을 지우지 않는다.** 시험에서 스킬은 제거되었지만 `~/.claude/CLAUDE.md`의 graphify 섹션은 그대로 남았다. 이미 없는 스킬을 가리키는 지침이 남으므로, 제거할 때 **사용자에게 보여 주고 확인받은 뒤 수동으로 그 섹션만 지운다.**
4. **코드만 있으면 무료·로컬이고, 문서·이미지·PDF가 있으면 토큰이 든다.**
   - 코드(`.py .ts .js .go .rs .java` 등)는 tree-sitter AST로 추출한다. LLM도 API 키도 필요 없다. 시험에서 코드 3파일 샘플은 약 2초에 끝났고 `Token cost: 0`이었다.
   - 문서(`.md .txt .rst`), 논문(`.pdf`), 이미지는 **의미 추출에 LLM을 쓴다.** 스킬은 서브에이전트를 병렬로 띄워 처리하도록 되어 있어 **현재 Claude 세션의 토큰을 쓴다.** 파일이 많을수록 비용과 시간이 늘어난다(스킬은 배치당 약 45초로 추정을 출력한다). `--mode deep`은 더 공격적으로 추정 연결을 만든다.
   - 시작 전에 코드만 있는지, 문서·이미지가 있는지와 대략의 규모를 사용자에게 알린다.
5. **`GEMINI_API_KEY` 또는 `GOOGLE_API_KEY`가 설정되어 있으면 의미 추출 내용이 Gemini로 전송된다.** 스킬은 이 환경변수가 있으면 서브에이전트 대신 Gemini 백엔드를 쓰도록 되어 있고 사용자에게 묻지 않는다. **문서·이미지 처리 전에 이 변수가 설정되어 있는지 확인한다(값은 출력하지 않는다).** 설정되어 있으면 사용자에게 알리고, 보내도 되는 자료인지 확인한다. 보낼 수 없다면 그 실행에서는 변수 없이 진행하도록 한다. 다른 API 키는 읽지 않는다고 스킬이 밝힌다.
6. **스킬이 실행 중에 스스로 설치·업그레이드할 수 있다.** 스킬의 파이썬 탐지 단계는 `graphify`를 못 찾으면 `uv tool install --upgrade graphifyy`(최신으로 업그레이드), uv가 없으면 `pip install graphifyy`, 그것도 실패하면 **`--break-system-packages`로 설치**하는 경로가 있다. 이런 자동 설치를 막으려면 **먼저 uv로 설치해 두고**(아래 2번), 시스템 파이썬에 `--break-system-packages`로 설치하게 두지 않는다.
7. **`graphify-out/`에는 프로젝트에서 뽑은 내용이 쌓인다.** 캐시와 `graph.json`에 코드 구조와 문서에서 추출한 개념이 들어 있다. 비공개 저장소라면 이 폴더를 커밋하거나 공유하기 전에 사용자에게 확인한다. 샘플 시험에서 Graphify는 `.gitignore`를 건드리지 않았다. `.gitignore`에 추가할지는 사용자에게 묻는다.
8. **`graph.html`을 열면 외부 CDN에 접속한다.** 생성된 파일이 `unpkg.com`의 `vis-network@9.1.6` 스크립트를 불러오는 것을 확인했다. 오프라인이면 그래프가 그려지지 않을 수 있고, 외부 스크립트가 로드되는 페이지에 코드 구조 데이터가 들어 있다는 점을 알린다.
9. **선택 기능은 동의가 있을 때만 쓴다.**
   - `graphify hook install`: git `post-commit` 훅을 설치해 커밋마다 그래프를 다시 만든다 (기존 훅이 있으면 덧붙인다). 저장소의 `.git/hooks`를 바꾸는 일이다.
   - `graphify claude install`: **프로젝트의** `CLAUDE.md`에 섹션을 써서 Claude가 코드 질문 전에 그래프를 확인하도록 상시 연동한다.
   - `--watch`: 백그라운드에서 변경을 감시한다. `--mcp`: MCP 서버를 띄운다. `/graphify add <URL>`: URL(논문, 트윗 등)을 가져와 `./raw`에 저장한다. `/graphify <GitHub 주소>`: 저장소를 복제해서 분석한다.
10. README의 "토큰 71.5배 절감" 같은 수치는 프로젝트의 주장이며 직접 검증하지 못했다. 사용자에게 효과를 약속하지 않는다.

## 1. 설치 여부 확인

```bash
graphify --help                      # 명령이 나오면 도구가 설치됨
ls ~/.claude/skills/graphify         # 스킬 설치 여부
grep -n graphify ~/.claude/CLAUDE.md # 전역 지침에 섹션이 있는지
uv --version                         # 설치에 필요 (또는 pipx)
```

## 2. 설치

권장: 이 패키지의 스크립트 (uv가 있어야 한다).

```bash
bash <경로>/jarvis-skills/install.sh --with-graphify
```

수동:

```bash
uv tool install graphifyy==0.9.95    # y 두 개. 시험한 버전으로 고정
graphify install                     # 스킬 설치 + ~/.claude/CLAUDE.md 에 섹션 추가
```

- 두 번째 명령 전에 `~/.claude/CLAUDE.md`가 있으면 백업한다.
- Python 3.10 이상이 필요하다. macOS에서 `pip install`이 externally-managed 오류를 내면 `pipx install graphifyy`를 쓰라고 README가 안내한다. 윈도우에서 `graphify`를 못 찾으면 파이썬 Scripts 폴더를 PATH에 추가하거나 `pipx`/`uv tool`을 쓴다.
- **uv가 없으면** 사용자에게 알린다. uv 설치는 원격 스크립트를 셸에 바로 넘기는 방식이라 자동으로 실행하지 않고, 사용자가 승인했을 때만 한다.
- 설치 후 Claude Code를 완전히 종료했다가 다시 켠다.

## 3. 사용

Claude Code에서 프로젝트 폴더를 열고:

```text
/graphify .
```

- 코드만 있는 프로젝트는 AST로 빠르게 끝난다. 문서·이미지가 있으면 4번(비용)과 5번(Gemini 키)을 사용자에게 알린 뒤 진행한다.
- 이후 보고서와 그래프로 질문한다: 예) "graphify-out 보고서 보고 이 프로젝트에서 결제 흐름이 어디서 시작되는지 알려 줘".
- 터미널에서도 쓸 수 있다 (그래프가 이미 있을 때):

```bash
graphify update <폴더>               # 코드 변경분만 다시 추출 (LLM 불필요)
graphify query "where does checkout start"
graphify god-nodes --top 10          # 가장 많이 연결된 핵심 노드
graphify explain "start_checkout()"  # 노드와 이웃 설명
graphify path "run()" "save_order()" # 두 노드 사이 최단 경로
```

샘플(코드 3파일)에서 `god-nodes`는 `start_checkout()`을 가장 많이 연결된 노드로 꼽았고, `query`는 `run() → start_checkout() → calculate_total()` 호출 관계를 `EXTRACTED`(코드에서 직접 확인)로 보여 주었다.

## 결과 확인과 보고

- 처리한 파일 수와 종류(코드/문서/이미지), 의미 추출을 했는지, 어떤 방식(세션 서브에이전트 또는 Gemini)이었는지
- `GRAPH_REPORT.md`의 핵심 노드와 요약. **`INFERRED`·`AMBIGUOUS` 연결은 추정이라고 구분해서** 말한다.
- 그래프가 코드의 실제 동작을 증명하지는 않는다. 정적 분석(호출 관계 추출)이라 동적 호출, 리플렉션, 설정 기반 연결은 빠질 수 있다. 중요한 결론은 해당 코드를 직접 확인하고 말한다.
- 결과물 위치(`graphify-out/`)와 커밋 여부에 대한 안내

## 지켜야 할 것

- **전역 `CLAUDE.md`를 바꾸는 설치는 사용자에게 알리고 백업한 뒤** 한다. 제거 시 남은 섹션을 임의로 지우지 않고 확인받는다.
- `GEMINI_API_KEY`/`GOOGLE_API_KEY` 설정 여부를 확인하고(값은 출력하지 않음), 설정되어 있으면 문서·이미지 처리 전에 사용자에게 알린다.
- 비공개 문서·PDF·이미지를 처리하기 전에 의미 추출이 외부(모델 제공자, Gemini)로 전달될 수 있음을 알린다.
- 훅 설치, 프로젝트 `CLAUDE.md` 연동, `--watch`, `--mcp`, URL·저장소 가져오기는 사용자 동의 후에만 한다.
- `--break-system-packages` 같은 시스템 파이썬 설치를 하지 않는다.
- `graphify-out/`을 사용자 확인 없이 커밋하거나 공유하지 않는다. 설치된 스킬 파일은 수정하지 않는다.

## 막힐 때

| 증상 | 해결 |
|---|---|
| `graphify: command not found` | uv의 bin 폴더(`uv tool dir --bin`)가 PATH에 있는지 확인, `uv tool update-shell` 후 새 터미널 |
| 설치했는데 `/graphify`를 모름 | Claude Code 완전 종료 후 재시작. `~/.claude/skills/graphify/SKILL.md` 확인 |
| 그래프가 비어 있음 | 지원 확장자(`.py .ts .js .go .rs .java .c .cpp .rb .cs .kt .scala .php`)가 있는지 확인 |
| `graph.html`이 비어 보임 | 인터넷이 `unpkg.com`에 닿는지 확인 (스크립트를 CDN에서 불러옴) |
| 제거 후에도 `/graphify` 지침이 남음 | 전역 `~/.claude/CLAUDE.md`의 `# graphify` 섹션이 남은 것. 확인 후 수동 삭제 |

공식 자료: <https://github.com/Graphify-Labs/graphify> · PyPI `graphifyy` · <https://docs.graphify.com>
