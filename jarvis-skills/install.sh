#!/usr/bin/env bash
# Jarvis 스킬 10종 설치 스크립트 (필수 5종 + PPT 도구 5종)
#
#   ./install.sh                         jarvis-* 스킬 10개를 현재 폴더의 .claude/skills 에 복사
#   ./install.sh --global                계정 전체(~/.claude/skills)에 복사
#   ./install.sh --with-upstream         + 공식 스킬 원본 4개와 GSD 설치 (네트워크 필요)
#   ./install.sh --with-browser          + agent-browser 명령줄 도구와 자동화용 크롬 설치
#   ./install.sh --with-ppt              + PPT 도구 설치: document-skills, PPT Master(+파이썬 부품),
#                                          frontend-slides 플러그인과 PowerPoint MCP 서버 (claude CLI 필요)
#   ./install.sh --with-official-plugins + 공식 마켓 플러그인 5종: frontend-design, superpowers,
#                                          code-review, context7, code-simplifier (claude CLI 필요)
#   환경변수 PPT_PYTHON=<python 경로>    PPT Master 부품을 설치할 파이썬 (기본 python3, 가상환경용)
#
# 원칙: 이미 있는 폴더·파일은 덮어쓰지 않고 건너뛴다. 마지막에 항상 검증하고,
#       하나라도 실패하면 완료로 보고하지 않고 종료 코드 1을 돌려준다.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCOPE=local
WITH_UPSTREAM=0
WITH_BROWSER=0
WITH_PPT=0
WITH_OFFICIAL=0

JARVIS_SKILLS=(
  jarvis-agent-browser jarvis-find-skills jarvis-gsd jarvis-design-taste jarvis-mcp-builder
  jarvis-ppt-document-skills jarvis-ppt-master jarvis-ppt-frontend-slides jarvis-ppt-slidev jarvis-ppt-powerpoint-mcp
  jarvis-frontend-design jarvis-superpowers jarvis-code-review jarvis-context7 jarvis-code-simplifier
)
UPSTREAM_SKILLS=(agent-browser find-skills design-taste-frontend mcp-builder)
PPT_PLUGINS=(document-skills@anthropic-agent-skills ppt-master@ppt-master frontend-slides@frontend-slides)
OFFICIAL_PLUGINS=(
  frontend-design@claude-plugins-official superpowers@claude-plugins-official code-review@claude-plugins-official
  context7@claude-plugins-official code-simplifier@claude-plugins-official
)

usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"; }

for arg in "$@"; do
  case "$arg" in
    --global) SCOPE=global ;;
    --local) SCOPE=local ;;
    --with-upstream) WITH_UPSTREAM=1 ;;
    --with-browser) WITH_BROWSER=1 ;;
    --with-ppt) WITH_PPT=1 ;;
    --with-official-plugins) WITH_OFFICIAL=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "알 수 없는 옵션: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

if [ "$SCOPE" = global ]; then
  BASE="$HOME/.claude"
  SKILL_FLAGS=(-g)
  GSD_FLAG=--global
  SETTINGS_FILE="$BASE/settings.json"
  CLAUDE_SCOPE=user
else
  BASE="$PWD/.claude"
  SKILL_FLAGS=()
  GSD_FLAG=--local
  SETTINGS_FILE="$BASE/settings.local.json"
  CLAUDE_SCOPE=local
fi
TARGET="$BASE/skills"
GSD_MARKER="$BASE/commands/gsd-help.md"
PLUGIN_ROOT="${CLAUDE_CONFIG_DIR:-$HOME/.claude}/plugins"
PPT_PY="${PPT_PYTHON:-python3}"

FAILURES=()
say()  { printf '%s\n' "$*"; }
step() { printf '\n== %s ==\n' "$*"; }
fail() { FAILURES+=("$*"); printf '실패: %s\n' "$*" >&2; }

have_claude() { command -v claude >/dev/null 2>&1; }

# 플러그인 하나를 설치한다. 이미 있으면 건너뛰고, 마켓이 없을 때만 등록한다.
# (결과를 변수에 담아 here-string 으로 grep 한다: pipefail 아래에서 grep -q 의 SIGPIPE 오탐을 피하기 위해)
install_plugin() { # <마켓 주소> <플러그인@마켓>
  local source="$1" id="$2" market="${2#*@}" listing
  listing="$(claude plugin list 2>/dev/null || true)"
  if grep -q -F "$id" <<<"$listing"; then say "건너뜀 (이미 있음): $id"; return; fi
  listing="$(claude plugin marketplace list 2>/dev/null || true)"
  if ! grep -q -F "$market" <<<"$listing"; then
    if ! claude plugin marketplace add "$source" --scope "$CLAUDE_SCOPE"; then fail "마켓 등록 실패: $source"; return; fi
  fi
  if claude plugin install "$id" --scope "$CLAUDE_SCOPE"; then say "설치 완료: $id"; else fail "플러그인 설치 실패: $id"; fi
}

# claude mcp list 에서 <패턴>(줄 시작 기준) 서버가 Connected 인지 확인한다. 파이프 없이 case 로 비교한다.
mcp_connected() { # <줄 시작 패턴, 예: 'ppt:*'>
  local listing line
  listing="$(claude mcp list 2>&1 || true)"
  while IFS= read -r line; do
    case "$line" in
      $1) case "$line" in *Connected*) return 0 ;; esac ;;
    esac
  done <<<"$listing"
  return 1
}

verify_plugins() { # <플러그인@마켓>...
  local listing id
  listing="$(claude plugin list 2>/dev/null || true)"
  for id in "$@"; do
    if grep -q -F "$id" <<<"$listing"; then say "OK   플러그인 $id"; else fail "플러그인이 설치되지 않음: $id"; fi
  done
}

node_ok() {
  command -v node >/dev/null 2>&1 || return 1
  local v major minor
  v="$(node --version)"; v="${v#v}"
  major="${v%%.*}"; minor="${v#*.}"; minor="${minor%%.*}"
  [ "$major" -gt 22 ] || { [ "$major" -eq 22 ] && [ "$minor" -ge 20 ]; }
}

say "설치 범위: $SCOPE  →  $TARGET"

# 1) jarvis-* 스킬 복사 (있으면 건너뜀)
step "1. jarvis 스킬 ${#JARVIS_SKILLS[@]}개 복사"
mkdir -p "$TARGET"
for s in "${JARVIS_SKILLS[@]}"; do
  if [ -e "$TARGET/$s" ]; then
    say "건너뜀 (이미 있음): $s"
  elif [ -f "$HERE/skills/$s/SKILL.md" ]; then
    cp -R "$HERE/skills/$s" "$TARGET/$s" && say "복사 완료: $s"
  else
    fail "원본을 찾을 수 없음: $HERE/skills/$s"
  fi
done

# 2) 공식 스킬 원본 + GSD
if [ "$WITH_UPSTREAM" -eq 1 ]; then
  step "2. 공식 스킬 원본 설치"
  if ! node_ok; then
    fail "Node.js 22.20 이상이 필요합니다 (현재: $(node --version 2>/dev/null || echo '없음')). https://nodejs.org 에서 LTS를 설치하세요."
  else
    say "Node $(node --version) 확인"
    install_skill() { # <이름> <npx 인자...>
      local name="$1"; shift
      if [ -e "$TARGET/$name" ]; then say "건너뜀 (이미 있음): $name"; return; fi
      if npx --yes skills@latest add "$@" --agent claude-code --yes --copy ${SKILL_FLAGS[@]+"${SKILL_FLAGS[@]}"}; then
        say "설치 완료: $name"
      else
        fail "스킬 설치 실패: $name"
      fi
    }
    install_skill agent-browser        vercel-labs/agent-browser
    install_skill find-skills          vercel-labs/skills --skill find-skills
    install_skill design-taste-frontend https://github.com/Leonxlnx/taste-skill --skill design-taste-frontend
    install_skill mcp-builder          anthropics/skills --skill mcp-builder

    step "3. GSD Core 설치"
    if [ -f "$GSD_MARKER" ]; then
      say "건너뜀 (이미 있음): GSD ($GSD_MARKER)"
    else
      # GSD 설치기는 Claude Code 설정 파일에 훅을 추가하므로 먼저 백업한다.
      if [ -f "$SETTINGS_FILE" ]; then
        BACKUP="$SETTINGS_FILE.bak-$(date +%Y%m%d%H%M%S)"
        cp "$SETTINGS_FILE" "$BACKUP" && say "설정 백업: $BACKUP"
      fi
      if npx --yes @opengsd/gsd-core@latest --claude "$GSD_FLAG"; then
        say "GSD 설치 완료. Claude Code를 완전히 종료했다가 다시 켠 뒤 /gsd-new-project 를 실행하세요."
      else
        fail "GSD 설치 실패"
      fi
    fi
  fi
fi

# 3) agent-browser 명령줄 도구
if [ "$WITH_BROWSER" -eq 1 ]; then
  step "4. agent-browser 명령줄 도구"
  if agent-browser --version >/dev/null 2>&1; then
    say "건너뜀 (이미 있음): agent-browser $(agent-browser --version)"
  elif ! command -v npm >/dev/null 2>&1; then
    fail "npm 이 없습니다. Node.js 를 먼저 설치하세요."
  elif npm install -g agent-browser && agent-browser install; then
    say "agent-browser 설치 완료"
  else
    fail "agent-browser 설치 실패 (리눅스에서 브라우저가 안 뜨면: agent-browser install --with-deps)"
  fi
fi

# 4) PPT 도구 5종 (Slidev 는 설치할 것이 없다: 새 발표를 만들 때 `npm init slidev` 를 쓴다)
if [ "$WITH_PPT" -eq 1 ]; then
  step "5. PPT 도구 설치"
  if ! have_claude; then
    fail "claude CLI 가 없습니다 (https://claude.com/claude-code). PPT 도구 설치를 건너뜁니다."
  else
    install_plugin anthropics/skills document-skills@anthropic-agent-skills
    install_plugin hugohe3/ppt-master ppt-master@ppt-master
    # frontend-slides 는 짧은 owner/repo 형태가 SSH 로 시도될 수 있어 HTTPS 전체 주소를 쓴다.
    install_plugin https://github.com/zarazhangrui/frontend-slides frontend-slides@frontend-slides

    # PPT Master 는 플러그인과 별개로 파이썬 부품이 필요하다. 빠지면 작업이 마지막에 멈춘다.
    REQ="$(ls -t "$PLUGIN_ROOT"/cache/ppt-master/ppt-master/*/ppt-master/requirements.txt 2>/dev/null | head -1 || true)"
    if [ -z "$REQ" ]; then
      fail "PPT Master 의 requirements.txt 를 찾을 수 없음 ($PLUGIN_ROOT)"
    elif ! command -v "$PPT_PY" >/dev/null 2>&1; then
      fail "파이썬을 찾을 수 없음: $PPT_PY"
    elif ! "$PPT_PY" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)'; then
      fail "PPT Master 는 Python 3.10 이상이 필요합니다 (현재: $("$PPT_PY" --version 2>&1))"
    elif "$PPT_PY" -m pip install -q -r "$REQ"; then
      say "PPT Master 파이썬 부품 확인 완료 (이미 설치된 것은 그대로 둠)"
    else
      fail "PPT Master 파이썬 부품 설치 실패. 'externally-managed-environment' 나 'installed by debian' 같은 시스템 파이썬 충돌이면: python3 -m venv ~/ppt-venv && PPT_PYTHON=~/ppt-venv/bin/python 으로 다시 실행하세요. (가상환경에 설치했다면 PPT Master 스크립트도 그 파이썬으로 실행해야 합니다.)"
    fi

    # PowerPoint MCP: mcp 2.x 에서 FastMCP 가 없어져 서버가 시작 직후 종료되므로 'mcp<2' 로 고정한다.
    if ! command -v uvx >/dev/null 2>&1; then
      fail "uv(uvx) 가 없습니다. https://docs.astral.sh/uv/ 에서 설치 후 다시 실행하세요. (PowerPoint MCP)"
    elif claude mcp get ppt >/dev/null 2>&1; then
      say "건너뜀 (이미 있음): MCP 서버 ppt"
    elif claude mcp add --scope "$CLAUDE_SCOPE" ppt -- uvx --from office-powerpoint-mcp-server --with 'mcp<2' ppt_mcp_server; then
      say "등록 완료: MCP 서버 ppt"
    else
      fail "PowerPoint MCP 등록 실패"
    fi
    say "PPT 도구를 쓰려면 Claude Code를 완전히 종료했다가 다시 켜세요. Slidev 는 새 폴더에서 'npm init slidev' 로 시작합니다."
  fi
fi

# 5) 공식 마켓 플러그인 5종
if [ "$WITH_OFFICIAL" -eq 1 ]; then
  step "6. 공식 마켓 플러그인 설치"
  if ! have_claude; then
    fail "claude CLI 가 없습니다 (https://claude.com/claude-code). 공식 플러그인 설치를 건너뜁니다."
  else
    for id in "${OFFICIAL_PLUGINS[@]}"; do
      install_plugin anthropics/claude-plugins-official "$id"
    done
    say "참고: superpowers 는 Claude 의 작업 방식을 바꿉니다(모든 세션 시작 때 스킬 사용 지침이 주입됨). 끄려면: claude plugin disable superpowers@claude-plugins-official"
    say "참고: code-review 는 실행하면 GitHub PR 에 댓글을 게시하고, context7 은 외부 서비스(mcp.context7.com)에 연결합니다."
    say "공식 플러그인을 쓰려면 Claude Code를 완전히 종료했다가 다시 켜세요."
  fi
fi

# 6) 검증
step "검증"
for s in "${JARVIS_SKILLS[@]}"; do
  if [ -f "$TARGET/$s/SKILL.md" ]; then say "OK   $s"; else fail "$s 의 SKILL.md 가 없음"; fi
done
if [ "$WITH_UPSTREAM" -eq 1 ]; then
  for s in "${UPSTREAM_SKILLS[@]}"; do
    if [ -f "$TARGET/$s/SKILL.md" ]; then say "OK   $s"; else fail "$s 의 SKILL.md 가 없음"; fi
  done
  if [ -f "$GSD_MARKER" ]; then say "OK   GSD ($GSD_MARKER)"; else fail "GSD 가 설치되지 않음 ($GSD_MARKER)"; fi
fi
if [ "$WITH_BROWSER" -eq 1 ]; then
  if v="$(agent-browser --version 2>&1)"; then say "OK   agent-browser $v"; else fail "agent-browser --version 실패"; fi
fi

if [ "$WITH_PPT" -eq 1 ] && have_claude; then
  verify_plugins "${PPT_PLUGINS[@]}"
  # requirements.txt 의 패키지를 모두 불러올 수 있는지 확인한다 (일부만 설치된 상태를 걸러낸다).
  if "$PPT_PY" -c 'import yaml, pptx, xlsxwriter, pathops, uharfbuzz, edge_tts, fitz, mammoth, markdownify, ebooklib, nbconvert, openpyxl, PIL, numpy, requests, bs4, curl_cffi, flask, google.genai' >/dev/null 2>&1; then
    say "OK   PPT Master 파이썬 부품 ($PPT_PY)"
  else
    fail "PPT Master 파이썬 부품이 모두 설치되어 있지 않음 ($PPT_PY)"
  fi
  if mcp_connected 'ppt:*'; then say "OK   MCP 서버 ppt 연결됨"; else fail "MCP 서버 ppt 가 연결되지 않음 (claude mcp list 로 확인)"; fi
fi

if [ "$WITH_OFFICIAL" -eq 1 ] && have_claude; then
  verify_plugins "${OFFICIAL_PLUGINS[@]}"
  # context7 은 외부 서버에 붙는 MCP 라서 설치와 별개로 실제 연결을 확인한다.
  if mcp_connected 'plugin:context7:*'; then
    say "OK   MCP context7 연결됨"
  else
    fail "context7 MCP 가 연결되지 않음. mcp.context7.com 접속이 네트워크 정책(방화벽·프록시)에서 허용되는지 확인하세요 (claude mcp list)."
  fi
fi

if [ "${#FAILURES[@]}" -gt 0 ]; then
  printf '\n미완료 %d건:\n' "${#FAILURES[@]}" >&2
  printf ' - %s\n' "${FAILURES[@]}" >&2
  exit 1
fi
printf '\n모두 완료. 새로 설치한 스킬은 Claude Code를 완전히 종료했다가 다시 켜야 인식됩니다.\n'
