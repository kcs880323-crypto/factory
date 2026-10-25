#!/usr/bin/env bash
# Jarvis 스킬 25종 설치 스크립트 (안내 스킬 복사 + 선택 옵션으로 실제 도구 설치)
#
#   ./install.sh                         jarvis-* 스킬 25개를 현재 폴더의 .claude/skills 에 복사
#   ./install.sh --global                계정 전체(~/.claude/skills)에 복사
#   ./install.sh --with-upstream         + 공식 스킬 원본 4개와 GSD 설치 (네트워크 필요)
#   ./install.sh --with-browser          + agent-browser 명령줄 도구와 자동화용 크롬 설치
#   ./install.sh --with-ppt              + PPT 도구 설치: document-skills, PPT Master(+파이썬 부품),
#                                          frontend-slides 플러그인과 PowerPoint MCP 서버 (claude CLI 필요)
#   ./install.sh --with-official-plugins + 공식 마켓 플러그인 5종: frontend-design, superpowers,
#                                          code-review, context7, code-simplifier (claude CLI 필요)
#   ./install.sh --with-design-skills    + 디자인 스킬 3종: frontend-design, theme-factory, web-design-guidelines
#                                          (frontend-design 은 공식 플러그인이 이미 있으면 건너뜀)
#   ./install.sh --with-agent-reach      + Agent Reach (uv 필요, 기본 채널만). 계정 전체(~/.agent-reach)에 설치됨
#   ./install.sh --with-rea              + REA(앱 리버스 엔지니어링): Node/npx 필요. ~/.claude.json 에 MCP 등록 +
#                                          ~/.claude/skills 에 스킬 설치 (계정 전체). Hopper 는 설치하지 않음.
#                                          별도 프로필에만 설치하려면 CLAUDE_CONFIG_DIR=~/.claude-rea 를 붙여 실행
#   ./install.sh --with-graphify         + Graphify(코드베이스 지식 그래프): uv 필요. 스킬 설치 + ~/.claude/CLAUDE.md 에 섹션 추가 (계정 전체)
#   ./install.sh --with-video-skills     + 영상 제작 도구: HyperFrames 플러그인(v0.8.197 고정), Lemo-Opuscar 플러그인,
#                                          Collage B-roll 의 SKILL.md(커밋·해시 고정). MaxFusion MCP 는 등록하지 않음 (claude CLI·curl 필요)
#   ./install.sh --with-onetake          + onetake(고정 커밋 복제, 약 225MB). 비상업 용도 전용 라이선스(PolyForm Noncommercial)
#   (Claude Animation Base 는 프로젝트 폴더가 필요한 스타터 킷이라 설치하지 않음: jarvis-claude-animation-base 스킬 참고)
#   환경변수 PPT_PYTHON=<python 경로>    PPT Master 부품을 설치할 파이썬 (기본 python3, 가상환경용)
#   환경변수 REA_VERSION=<버전>          REA npm 버전 (기본: 시험한 6.7.0)
#   환경변수 GRAPHIFY_SPEC=<스펙>        Graphify PyPI 스펙 (기본: 시험한 graphifyy==0.9.95, y 가 두 개)
#   환경변수 AGENT_REACH_URL=<주소>      Agent Reach 설치 주소 (기본: 시험한 v1.6.0 릴리스 파일로 고정)
#   환경변수 HYPERFRAMES_REF=<태그>      HyperFrames 마켓 태그 (기본: 시험한 v0.8.197)
#   환경변수 ONETAKE_SHA=<커밋>          onetake 커밋 (기본: 시험한 013a61ac…)
#   환경변수 COLLAGE_SHA, COLLAGE_SHA256 Collage B-roll SKILL.md 의 커밋과 SHA-256 (둘 다 시험한 값이 기본. 바꾸면 해시도 함께 바꿔야 함)
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
WITH_AGENT_REACH=0
WITH_DESIGN=0
WITH_REA=0
WITH_GRAPHIFY=0
WITH_VIDEO=0
WITH_ONETAKE=0
# 직접 시험한 버전으로 고정한다 (바뀔 수 있는 최신 버전을 그대로 실행하지 않기 위해).
REA_VERSION="${REA_VERSION:-6.7.0}"
GRAPHIFY_SPEC="${GRAPHIFY_SPEC:-graphifyy==0.9.95}"
HYPERFRAMES_REF="${HYPERFRAMES_REF:-v0.8.197}"
ONETAKE_SHA="${ONETAKE_SHA:-013a61ac8d98883c73733f59c06e49e9ba444f14}"
COLLAGE_SHA="${COLLAGE_SHA:-2e9b8a93a2834e4d670700ab9e4827a8baf3b668}"
COLLAGE_SHA256="${COLLAGE_SHA256:-5272b49c6581b1677b9f65d3eb2aa912825c1247395074bce7ff6ee845ac0124}"

JARVIS_SKILLS=(
  jarvis-agent-browser jarvis-find-skills jarvis-gsd jarvis-design-taste jarvis-mcp-builder
  jarvis-ppt-document-skills jarvis-ppt-master jarvis-ppt-frontend-slides jarvis-ppt-slidev jarvis-ppt-powerpoint-mcp
  jarvis-frontend-design jarvis-superpowers jarvis-code-review jarvis-context7 jarvis-code-simplifier
  jarvis-agent-reach
  jarvis-theme-factory jarvis-web-design-guidelines
  jarvis-rea jarvis-graphify
  jarvis-hyperframes jarvis-onetake jarvis-collage-broll jarvis-claude-animation-base jarvis-lemo-opuscar
)
# 공식 문서의 'releases/latest/download/agent-reach.zip' 대신 같은 릴리스 파일을 버전으로 고정한다
# (v1.6.0 이 latest 일 때 두 주소는 같은 파일임을 확인함. 바뀔 수 있는 최신 파일을 그대로 실행하지 않기 위해).
AGENT_REACH_URL="${AGENT_REACH_URL:-https://github.com/Panniantong/Agent-Reach/releases/download/v1.6.0/agent-reach.zip}"
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
    --with-agent-reach) WITH_AGENT_REACH=1 ;;
    --with-design-skills) WITH_DESIGN=1 ;;
    --with-rea) WITH_REA=1 ;;
    --with-graphify) WITH_GRAPHIFY=1 ;;
    --with-video-skills) WITH_VIDEO=1 ;;
    --with-onetake) WITH_ONETAKE=1 ;;
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

# npx skills 로 스킬 하나를 설치한다. 이미 있으면 건너뛴다. 저장소는 반드시 --skill 로 하나만 고른다.
install_skill() { # <이름> <npx 인자...>
  local name="$1"; shift
  if [ -e "$TARGET/$name" ]; then say "건너뜀 (이미 있음): $name"; return; fi
  if npx --yes skills@latest add "$@" --agent claude-code --yes --copy ${SKILL_FLAGS[@]+"${SKILL_FLAGS[@]}"}; then
    say "설치 완료: $name"
  else
    fail "스킬 설치 실패: $name"
  fi
}

plugin_installed() { # <플러그인@마켓>
  local listing
  listing="$(claude plugin list 2>/dev/null || true)"
  grep -q -F "$1" <<<"$listing"
}

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

# agent-reach 실행 파일을 찾는다. uv 는 ~/.local/bin 에 설치하는데 PATH 에 없을 수 있어 그 폴더도 확인한다.
agent_reach_bin() {
  local bin_dir
  if command -v agent-reach >/dev/null 2>&1; then command -v agent-reach; return 0; fi
  bin_dir="$(uv tool dir --bin 2>/dev/null || true)"
  if [ -n "$bin_dir" ]; then
    if [ -x "$bin_dir/agent-reach" ]; then printf '%s\n' "$bin_dir/agent-reach"; return 0; fi
    if [ -x "$bin_dir/agent-reach.exe" ]; then printf '%s\n' "$bin_dir/agent-reach.exe"; return 0; fi
  fi
  return 1
}

# graphify 실행 파일을 찾는다. uv 가 ~/.local/bin 에 설치하는데 PATH 에 없을 수 있어 그 폴더도 확인한다.
graphify_bin() {
  local bin_dir
  if command -v graphify >/dev/null 2>&1; then command -v graphify; return 0; fi
  bin_dir="$(uv tool dir --bin 2>/dev/null || true)"
  if [ -n "$bin_dir" ]; then
    if [ -x "$bin_dir/graphify" ]; then printf '%s\n' "$bin_dir/graphify"; return 0; fi
    if [ -x "$bin_dir/graphify.exe" ]; then printf '%s\n' "$bin_dir/graphify.exe"; return 0; fi
  fi
  return 1
}

# REA 가 요구하는 Node 버전(문서 기준 22.23.4+ / 24.22.1+ / 26.12.1+)에 못 미치면 경고만 한다.
# (package.json 의 engines 는 ^22.19 || ^24.11 || >=26 으로 더 느슨하고, 22.22.0 에서 설치·MCP 연결을 확인함)
rea_node_note() {
  local v major rest minor patch ok=0
  v="$(node --version 2>/dev/null || true)"; v="${v#v}"
  major="${v%%.*}"; rest="${v#*.}"; minor="${rest%%.*}"; patch="${rest#*.}"
  case "$major" in
    22) { [ "$minor" -gt 23 ] || { [ "$minor" -eq 23 ] && [ "$patch" -ge 4 ]; }; } && ok=1 ;;
    24) { [ "$minor" -gt 22 ] || { [ "$minor" -eq 22 ] && [ "$patch" -ge 1 ]; }; } && ok=1 ;;
    *) [ "$major" -ge 26 ] 2>/dev/null && ok=1 ;;
  esac
  [ "$ok" -eq 1 ] || say "참고: REA 문서는 Node 22.23.4+ / 24.22.1+ / 26.12.1+ 를 요구합니다 (현재 v$v). 설치와 MCP 연결은 22.22.0 에서 확인했지만 분석 기능은 확인하지 못했습니다."
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

# 파일의 SHA-256 을 출력한다 (sha256sum → shasum → openssl 순서로 있는 도구를 쓴다).
sha256_of() { # <파일>
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
  elif command -v openssl >/dev/null 2>&1; then openssl dgst -sha256 "$1" | sed 's/^.*= //'
  else return 1
  fi
}

# 저장소의 고정 커밋 하나만 얕게 받는다. (git clone 은 그때그때의 최신을 받아서 쓰지 않는다.)
# 대상 폴더가 이미 있으면 건드리지 않고 2 를 돌려준다. 받다가 실패하면 이 함수가 만든 폴더만 지운다.
git_clone_pinned() { # <주소> <커밋> <대상 폴더>
  local url="$1" sha="$2" dest="$3"
  [ -n "$dest" ] || return 1
  [ ! -e "$dest" ] || return 2
  if mkdir -p "$dest" && git -C "$dest" init -q && git -C "$dest" remote add origin "$url" \
     && git -C "$dest" fetch -q --depth 1 origin "$sha" \
     && git -C "$dest" -c advice.detachedHead=false checkout -q FETCH_HEAD; then
    return 0
  fi
  rm -rf "$dest"
  return 1
}

# Collage B-roll: 고정 커밋의 SKILL.md 한 파일만 받고, 내려받은 내용의 SHA-256 이 시험한 값과 같을 때만 설치한다.
COLLAGE_INSTALLED_NOW=0
install_collage() {
  local dest="$TARGET/collage-broll-explainers" tmp url got
  if [ -e "$dest" ]; then say "건너뜀 (이미 있음): collage-broll-explainers"; return; fi
  if ! command -v curl >/dev/null 2>&1; then fail "curl 이 없습니다. (Collage B-roll)"; return; fi
  tmp="$(mktemp)"
  url="https://raw.githubusercontent.com/MegaTroll222/VOX-COLLAGE-BROLL/$COLLAGE_SHA/SKILL.md"
  if ! curl -fsSL "$url" -o "$tmp"; then fail "Collage B-roll SKILL.md 를 받지 못했습니다: $url"; rm -f "$tmp"; return; fi
  got="$(sha256_of "$tmp" || true)"
  if [ "$got" != "$COLLAGE_SHA256" ]; then
    fail "Collage B-roll SKILL.md 의 SHA-256 이 시험한 값과 다릅니다 (받은 값: ${got:-계산 불가}). 설치하지 않았습니다. 내용을 확인한 뒤 COLLAGE_SHA256 을 바꾸세요."
    rm -f "$tmp"; return
  fi
  if mkdir -p "$dest" && cp "$tmp" "$dest/SKILL.md"; then
    COLLAGE_INSTALLED_NOW=1
    say "설치 완료: collage-broll-explainers (커밋 ${COLLAGE_SHA:0:8}, SHA-256 확인)"
  else
    fail "Collage B-roll 설치 실패"
  fi
  rm -f "$tmp"
}

# 영상 도구에 필요한 시스템 도구가 없으면 알리기만 한다 (설치할 때가 아니라 렌더할 때 필요하고, 시스템 도구는 자동 설치하지 않는다).
video_tools_note() {
  local missing="" major
  command -v node >/dev/null 2>&1 || missing="$missing node"
  command -v ffmpeg >/dev/null 2>&1 || missing="$missing ffmpeg"
  command -v git >/dev/null 2>&1 || missing="$missing git"
  if [ -n "$missing" ]; then
    say "참고: 영상을 만들(렌더할) 때 필요한 도구가 없습니다:$missing  (설치만 할 때는 필요 없음. 자동으로 설치하지 않습니다.)"
  fi
  if command -v node >/dev/null 2>&1; then
    major="$(node --version)"; major="${major#v}"; major="${major%%.*}"
    [ "$major" -ge 22 ] 2>/dev/null || say "참고: HyperFrames 는 Node 22 이상이 필요합니다 (현재 $(node --version))."
  fi
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
    if [ -e "$TARGET/frontend-design" ]; then
      say "주의: frontend-design 이 이미 단독 스킬($TARGET/frontend-design)로 설치되어 있습니다. 플러그인 버전과 내용이 같아 중복되니 한쪽만 쓰세요."
    fi
    for id in "${OFFICIAL_PLUGINS[@]}"; do
      install_plugin anthropics/claude-plugins-official "$id"
    done
    say "참고: superpowers 는 Claude 의 작업 방식을 바꿉니다(모든 세션 시작 때 스킬 사용 지침이 주입됨). 끄려면: claude plugin disable superpowers@claude-plugins-official"
    say "참고: code-review 는 실행하면 GitHub PR 에 댓글을 게시하고, context7 은 외부 서비스(mcp.context7.com)에 연결합니다."
    say "공식 플러그인을 쓰려면 Claude Code를 완전히 종료했다가 다시 켜세요."
  fi
fi

# 6) 디자인 스킬 3종 (npx skills). frontend-design 은 공식 마켓 플러그인과 내용이 같아서(해시 동일 확인),
#    플러그인이 이미 설치되어 있으면 중복을 피해 건너뛴다.
if [ "$WITH_DESIGN" -eq 1 ]; then
  step "7. 디자인 스킬 설치"
  if ! node_ok; then
    fail "Node.js 22.20 이상이 필요합니다 (현재: $(node --version 2>/dev/null || echo '없음')). https://nodejs.org 에서 LTS를 설치하세요."
  else
    install_skill theme-factory          anthropics/skills --skill theme-factory
    install_skill web-design-guidelines  vercel-labs/agent-skills --skill web-design-guidelines
    if have_claude && plugin_installed frontend-design@claude-plugins-official; then
      say "건너뜀 (공식 플러그인으로 이미 설치됨): frontend-design"
    else
      install_skill frontend-design      anthropics/skills --skill frontend-design
    fi
    say "참고: web-design-guidelines 는 검토할 때마다 원격 규칙 파일(main 브랜치)을 읽어 와 그대로 따릅니다. 고정 주소와 안전 규칙은 jarvis-web-design-guidelines 스킬을 따르세요."
    say "참고: theme-factory 의 테마 글꼴(DejaVu, FreeSans 등)은 한글을 지원하지 않습니다. 한글 문서에는 한글 글꼴을 따로 정하세요."
  fi
fi

# 7) Agent Reach — 계정 전체에 설치된다 (~/.agent-reach, 그리고 ~/.claude 가 있으면 ~/.claude/skills/agent-reach 링크).
#    기본 채널만 준비하고, 로그인과 선택 채널(agent-reach add/login)은 사용자 동의 없이 하지 않는다.
if [ "$WITH_AGENT_REACH" -eq 1 ]; then
  step "8. Agent Reach 설치"
  AR="$(agent_reach_bin || true)"
  if [ -n "$AR" ]; then
    ar_version="$("$AR" version 2>&1 || true)"
    case "$ar_version" in
      *'"ok": true'*) say "건너뜀 (이미 있음): Agent Reach" ;;
      *) fail "이미 있는 agent-reach 가 1.6 이전 버전이거나 다른 프로그램입니다. 공식 install.md 의 업그레이드 절차를 따르세요. (PyPI 의 agent-reach 는 이 프로젝트가 아닙니다.)" ;;
    esac
  elif ! command -v uv >/dev/null 2>&1; then
    fail "uv 가 없습니다. 공식 설치 안내(docs.astral.sh/uv)를 보고 설치한 뒤 다시 실행하세요. 원격 설치 스크립트는 자동으로 실행하지 않습니다. (Agent Reach)"
  else
    ar_log="$(mktemp)"
    if uv tool install "$AGENT_REACH_URL" && AR="$(agent_reach_bin)" && "$AR" setup >"$ar_log" 2>&1; then
      say "설치 완료: Agent Reach (기본 채널: GitHub, YouTube, Bilibili, V2EX, 웹). 로그인·선택 채널은 설치하지 않았습니다."
      rm -f "$ar_log"
    else
      fail "Agent Reach 설치 실패 (위의 uv 오류, 또는 setup 출력 로그: $ar_log)"
    fi
  fi
  say "참고: Agent Reach 는 로그인이 필요한 사이트에서 내 Chrome 의 로그인을 복사해 쓸 수 있습니다. 로그인·선택 채널은 사용자 동의 후에만 진행하세요 (jarvis-agent-reach 스킬 참고)."
fi

# 8) REA (앱 리버스 엔지니어링) — 계정 전체에 설치된다.
#    setup 이 ~/.claude.json 에 MCP 서버를 등록하고 ~/.claude/skills 에 스킬을 설치한다(백업은 REA 가 만든다).
#    Hopper 같은 상용 분석 도구는 설치하지 않는다 (--install-hopper 를 쓰지 않음).
if [ "$WITH_REA" -eq 1 ]; then
  step "9. REA 설치"
  if ! node_ok; then
    fail "Node.js 22.20 이상이 필요합니다 (현재: $(node --version 2>/dev/null || echo '없음')). https://nodejs.org 에서 LTS를 설치하세요. (REA)"
  elif ! command -v npx >/dev/null 2>&1; then
    fail "npx 가 없습니다. Node.js(npm 포함)를 설치하세요. (REA)"
  else
    rea_node_note
    # REA 는 CLAUDE_CONFIG_DIR 을 따른다: 별도 프로필을 쓰면 그 안에만 등록·설치된다 (권장: 필요한 곳에서만 쓰기).
    if [ -n "${CLAUDE_CONFIG_DIR:-}" ]; then REA_CFG="$CLAUDE_CONFIG_DIR/.claude.json"; else REA_CFG="$HOME/.claude.json"; fi
    REA_SKILL="${CLAUDE_CONFIG_DIR:-$HOME/.claude}/skills/reverse-engineer-anything/SKILL.md"
    if [ -f "$REA_SKILL" ] && [ -f "$REA_CFG" ] && grep -q 'rea-agents@' "$REA_CFG"; then
      say "건너뜀 (이미 있음): REA (스킬과 MCP 등록)"
    else
      rea_log="$(mktemp)"
      if npx -y "rea-agents@$REA_VERSION" setup --client claude_code --yes --json >"$rea_log" 2>&1; then
        say "설치 완료: REA $REA_VERSION (MCP 등록 + reverse-engineer-anything 스킬). 분석 엔진(Hopper/Ghidra/IDA)은 설치하지 않았습니다."
        rm -f "$rea_log"
      else
        fail "REA 설치 실패 (setup 출력: $rea_log)"
      fi
    fi
    say "참고: REA 는 내가 소유했거나 분석이 허가된 소프트웨어에만 쓰세요. 런타임 분석은 대상 앱을 내 권한으로 실행합니다 (jarvis-rea 스킬 참고)."
  fi
fi

# 9) Graphify (코드베이스 지식 그래프) — 계정 전체에 설치된다.
#    graphify install 이 ~/.claude/skills/graphify 를 만들고 ~/.claude/CLAUDE.md 에 섹션을 추가한다 (기존 내용은 보존).
if [ "$WITH_GRAPHIFY" -eq 1 ]; then
  step "10. Graphify 설치"
  GB="$(graphify_bin || true)"
  if [ -n "$GB" ] && [ -f "$HOME/.claude/skills/graphify/SKILL.md" ]; then
    say "건너뜀 (이미 있음): Graphify"
  elif [ -z "$GB" ] && ! command -v uv >/dev/null 2>&1; then
    fail "uv 가 없습니다. 공식 설치 안내(docs.astral.sh/uv)를 보고 설치한 뒤 다시 실행하세요. 원격 설치 스크립트는 자동으로 실행하지 않습니다. (Graphify)"
  else
    gfy_log="$(mktemp)"
    CM="$HOME/.claude/CLAUDE.md"
    if [ -f "$CM" ] && ! grep -q '^# graphify' "$CM"; then
      cp "$CM" "$CM.bak-$(date +%Y%m%d%H%M%S)" && say "CLAUDE.md 백업: $CM.bak-*"
    fi
    if { [ -n "$GB" ] || uv tool install "$GRAPHIFY_SPEC"; } && GB="$(graphify_bin)" && "$GB" install >"$gfy_log" 2>&1; then
      say "설치 완료: Graphify ($GRAPHIFY_SPEC). 스킬 설치 + ~/.claude/CLAUDE.md 에 graphify 섹션 추가"
      rm -f "$gfy_log"
    else
      fail "Graphify 설치 실패 (위의 uv 오류, 또는 install 출력: $gfy_log)"
    fi
  fi
  say "참고: graphify uninstall 은 전역 ~/.claude/CLAUDE.md 에 추가한 graphify 섹션을 지우지 않습니다. 제거할 때 수동으로 지우세요 (jarvis-graphify 스킬 참고)."
fi

# 11) 영상 제작 도구 — HyperFrames·Lemo-Opuscar(플러그인), Collage B-roll(SKILL.md 한 파일).
#     MaxFusion MCP(제3자 유료 서비스, 계정 로그인)는 사용자 동의가 필요해서 등록하지 않는다.
#     Claude Animation Base 는 프로젝트 폴더가 필요한 스타터 킷이라 설치하지 않는다 (jarvis-claude-animation-base 스킬 참고).
if [ "$WITH_VIDEO" -eq 1 ]; then
  step "11. 영상 제작 도구 설치"
  video_tools_note
  if ! have_claude; then
    fail "claude CLI 가 없습니다 (https://claude.com/claude-code). HyperFrames·Lemo-Opuscar 플러그인 설치를 건너뜁니다."
  else
    # 마켓 주소 뒤의 #태그 로 시험한 버전에 고정한다 (태그 이름은 되지만 커밋 해시는 안 됨을 확인함).
    install_plugin "heygen-com/hyperframes#$HYPERFRAMES_REF" hyperframes@hyperframes
    # Lemo-Opuscar 는 쓸 만한 버전 태그가 없어 설치 시점의 기본 브랜치를 받는다. 라이브러리는 어차피 실행 때 main 을 따라간다.
    install_plugin lemomo-ai/lemo-opuscar lemo-opuscar@lemolab
    say "참고: HyperFrames 는 텔레메트리가 기본으로 켜져 있고 렌더 뒤 피드백 전송을 안내합니다. 끄려면: npx hyperframes telemetry disable (jarvis-hyperframes 스킬 참고)."
    say "참고: Lemo-Opuscar 의 라이브러리(~/lemo-opuscar)는 쓸 때마다 업스트림 main 으로 갱신되고, 처음 쓸 때 npm 패키지·브라우저·파이썬 패키지를 설치합니다 (jarvis-lemo-opuscar 스킬 참고)."
  fi
  install_collage
  say "참고: Collage B-roll 은 MaxFusion MCP(제3자 유료 서비스)가 있어야 동작합니다. 동의 없이 등록하지 않았습니다 (jarvis-collage-broll 스킬 참고)."
  say "영상 도구를 쓰려면 Claude Code를 완전히 종료했다가 다시 켜세요."
fi

# 12) onetake — 고정 커밋을 스킬 폴더에 복제한다 (약 225MB). 라이선스는 PolyForm Noncommercial(비상업 전용).
#     이 옵션을 직접 붙였다는 것이 "비상업 용도"라는 확인이 아니므로, 스킬(jarvis-onetake)이 사용 전에 다시 묻는다.
if [ "$WITH_ONETAKE" -eq 1 ]; then
  step "12. onetake 설치"
  if ! command -v git >/dev/null 2>&1; then
    fail "git 이 없습니다. (onetake)"
  else
    rc=0
    git_clone_pinned https://github.com/feitangyuan/onetake.git "$ONETAKE_SHA" "$TARGET/onetake" || rc=$?
    case "$rc" in
      0) say "설치 완료: onetake (커밋 ${ONETAKE_SHA:0:8})" ;;
      2) say "건너뜀 (이미 있음): onetake" ;;
      *) fail "onetake 설치 실패 (커밋 $ONETAKE_SHA 를 받지 못했습니다)" ;;
    esac
  fi
  say "주의: onetake 는 PolyForm Noncommercial 1.0.0 입니다. 개인·학습·연구 등 비상업 용도만 허용되고 상업적 사용은 제작자의 별도 라이선스가 필요합니다."
  say "참고: 파이썬 패키지(playwright, numpy, scipy 등)는 설치하지 않았습니다. 가상환경에 설치하세요 (jarvis-onetake 스킬 참고)."
fi

# 검증
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

if [ "$WITH_DESIGN" -eq 1 ]; then
  for s in theme-factory web-design-guidelines; do
    if [ -f "$TARGET/$s/SKILL.md" ]; then say "OK   $s"; else fail "$s 의 SKILL.md 가 없음"; fi
  done
  if [ -f "$TARGET/frontend-design/SKILL.md" ]; then
    say "OK   frontend-design (단독 스킬)"
  elif have_claude && plugin_installed frontend-design@claude-plugins-official; then
    say "OK   frontend-design (공식 플러그인)"
  else
    fail "frontend-design 이 설치되지 않음 (단독 스킬도 공식 플러그인도 없음)"
  fi
fi

if [ "$WITH_REA" -eq 1 ]; then
  REA_SKILL="${CLAUDE_CONFIG_DIR:-$HOME/.claude}/skills/reverse-engineer-anything/SKILL.md"
  if [ -f "$REA_SKILL" ]; then say "OK   REA 스킬 (reverse-engineer-anything)"; else fail "REA 스킬이 설치되지 않음 ($REA_SKILL)"; fi
  if have_claude; then
    if mcp_connected 'rea:*'; then say "OK   REA MCP 연결됨"; else fail "REA MCP 가 연결되지 않음 (claude mcp list 로 확인)"; fi
  else
    fail "claude CLI 가 없어 REA MCP 연결을 확인할 수 없음"
  fi
fi

if [ "$WITH_GRAPHIFY" -eq 1 ]; then
  GB="$(graphify_bin || true)"
  if [ -z "$GB" ]; then
    fail "graphify 실행 파일을 찾을 수 없음"
  else
    if "$GB" --help >/dev/null 2>&1; then say "OK   graphify 실행됨"; else fail "graphify --help 가 실패함"; fi
  fi
  if [ -f "$HOME/.claude/skills/graphify/SKILL.md" ]; then say "OK   Graphify 스킬"; else fail "Graphify 스킬이 설치되지 않음 (~/.claude/skills/graphify)"; fi
  if [ -f "$HOME/.claude/CLAUDE.md" ] && grep -q 'graphify' "$HOME/.claude/CLAUDE.md"; then say "OK   ~/.claude/CLAUDE.md 에 graphify 섹션 있음"; else fail "~/.claude/CLAUDE.md 에 graphify 섹션이 없음"; fi
fi

if [ "$WITH_VIDEO" -eq 1 ]; then
  if have_claude; then
    verify_plugins hyperframes@hyperframes lemo-opuscar@lemolab
  else
    fail "claude CLI 가 없어 영상 플러그인(hyperframes, lemo-opuscar)을 확인할 수 없음"
  fi
  collage_file="$TARGET/collage-broll-explainers/SKILL.md"
  if [ -f "$collage_file" ]; then
    got="$(sha256_of "$collage_file" || true)"
    if [ "$got" = "$COLLAGE_SHA256" ]; then
      say "OK   collage-broll-explainers (시험한 SHA-256 과 일치)"
    elif [ "$COLLAGE_INSTALLED_NOW" -eq 1 ]; then
      fail "collage-broll-explainers 의 SHA-256 이 시험한 값과 다름"
    else
      say "참고: 이미 있던 collage-broll-explainers 는 시험한 내용과 다릅니다. 확인하세요."
    fi
  else
    fail "collage-broll-explainers 의 SKILL.md 가 없음"
  fi
fi

if [ "$WITH_ONETAKE" -eq 1 ]; then
  if [ -f "$TARGET/onetake/SKILL.md" ]; then
    say "OK   onetake"
    onetake_head="$(git -C "$TARGET/onetake" rev-parse HEAD 2>/dev/null || true)"
    [ "$onetake_head" = "$ONETAKE_SHA" ] || say "참고: onetake 가 시험한 커밋(${ONETAKE_SHA:0:8})이 아닙니다 (현재: ${onetake_head:0:8})."
  else
    fail "onetake 의 SKILL.md 가 없음 ($TARGET/onetake)"
  fi
fi

if [ "$WITH_AGENT_REACH" -eq 1 ]; then
  AR="$(agent_reach_bin || true)"
  if [ -z "$AR" ]; then
    fail "agent-reach 실행 파일을 찾을 수 없음"
  else
    ar_version="$("$AR" version 2>&1 || true)"
    ar_skill="$("$AR" skill 2>&1 || true)"
    case "$ar_version" in *'"ok": true'*) say "OK   Agent Reach 실행됨" ;; *) fail "agent-reach version 이 정상 응답하지 않음" ;; esac
    case "$ar_skill" in *'"installed": true'*) say "OK   Agent Reach 스킬 설치됨" ;; *) fail "Agent Reach 스킬이 설치되지 않음 (agent-reach skill 로 확인)" ;; esac
  fi
fi

if [ "${#FAILURES[@]}" -gt 0 ]; then
  printf '\n미완료 %d건:\n' "${#FAILURES[@]}" >&2
  printf ' - %s\n' "${FAILURES[@]}" >&2
  exit 1
fi
printf '\n모두 완료. 새로 설치한 스킬은 Claude Code를 완전히 종료했다가 다시 켜야 인식됩니다.\n'
