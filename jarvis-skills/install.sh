#!/usr/bin/env bash
# Jarvis 스킬 5종 설치 스크립트
#
#   ./install.sh                         jarvis-* 스킬 5개를 현재 폴더의 .claude/skills 에 복사
#   ./install.sh --global                계정 전체(~/.claude/skills)에 복사
#   ./install.sh --with-upstream         + 공식 스킬 원본 4개와 GSD 설치 (네트워크 필요)
#   ./install.sh --with-browser          + agent-browser 명령줄 도구와 자동화용 크롬 설치
#
# 원칙: 이미 있는 폴더·파일은 덮어쓰지 않고 건너뛴다. 마지막에 항상 검증하고,
#       하나라도 실패하면 완료로 보고하지 않고 종료 코드 1을 돌려준다.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCOPE=local
WITH_UPSTREAM=0
WITH_BROWSER=0

JARVIS_SKILLS=(jarvis-agent-browser jarvis-find-skills jarvis-gsd jarvis-design-taste jarvis-mcp-builder)
UPSTREAM_SKILLS=(agent-browser find-skills design-taste-frontend mcp-builder)

usage() { sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

for arg in "$@"; do
  case "$arg" in
    --global) SCOPE=global ;;
    --local) SCOPE=local ;;
    --with-upstream) WITH_UPSTREAM=1 ;;
    --with-browser) WITH_BROWSER=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "알 수 없는 옵션: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

if [ "$SCOPE" = global ]; then
  BASE="$HOME/.claude"
  SKILL_FLAGS=(-g)
  GSD_FLAG=--global
  SETTINGS_FILE="$BASE/settings.json"
else
  BASE="$PWD/.claude"
  SKILL_FLAGS=()
  GSD_FLAG=--local
  SETTINGS_FILE="$BASE/settings.local.json"
fi
TARGET="$BASE/skills"
GSD_MARKER="$BASE/commands/gsd-help.md"

FAILURES=()
say()  { printf '%s\n' "$*"; }
step() { printf '\n== %s ==\n' "$*"; }
fail() { FAILURES+=("$*"); printf '실패: %s\n' "$*" >&2; }

node_ok() {
  command -v node >/dev/null 2>&1 || return 1
  local v major minor
  v="$(node --version)"; v="${v#v}"
  major="${v%%.*}"; minor="${v#*.}"; minor="${minor%%.*}"
  [ "$major" -gt 22 ] || { [ "$major" -eq 22 ] && [ "$minor" -ge 20 ]; }
}

say "설치 범위: $SCOPE  →  $TARGET"

# 1) jarvis-* 스킬 복사 (있으면 건너뜀)
step "1. jarvis 스킬 5개 복사"
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

# 4) 검증
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

if [ "${#FAILURES[@]}" -gt 0 ]; then
  printf '\n미완료 %d건:\n' "${#FAILURES[@]}" >&2
  printf ' - %s\n' "${FAILURES[@]}" >&2
  exit 1
fi
printf '\n모두 완료. 새로 설치한 스킬은 Claude Code를 완전히 종료했다가 다시 켜야 인식됩니다.\n'
