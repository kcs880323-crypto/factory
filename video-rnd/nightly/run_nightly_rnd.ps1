# 자비스 PC 새벽 03:30 자동 개발 실행 스크립트 (Windows PowerShell)
param(
    [string]$RepoPath = "$env:USERPROFILE\factory",
    [string]$Branch = "claude/jarvis-skills-delivery-75yvpx"
)

$ErrorActionPreference = "Stop"
$LogDir = Join-Path $RepoPath "video-rnd\nightly\logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$Today = Get-Date -Format "yyyy-MM-dd"
$LogFile = Join-Path $LogDir "$Today.log"

function Log($msg) {
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    "[$ts] $msg" | Tee-Object -FilePath $LogFile -Append
}

try {
    Log "=== 자비스 새벽 R&D 자동 개발 시작 ==="
    Set-Location $RepoPath
    git pull origin $Branch 2>&1 | Tee-Object -FilePath $LogFile -Append

    $QueueFile = Join-Path $RepoPath "video-rnd\nightly\NIGHTLY_QUEUE.md"
    if (-not (Test-Path $QueueFile)) {
        Log "NIGHTLY_QUEUE.md 없음 — 종료"
        exit 0
    }

    $QueueText = Get-Content -Raw -Encoding UTF8 $QueueFile
    if ($QueueText -notmatch '\[READY\]') {
        Log "[READY] 대기 항목 0건 — 토큰 사용 없이 조용히 종료"
        exit 0
    }

    $PromptFile = Join-Path $RepoPath "video-rnd\nightly\nightly_prompt.md"
    $PromptText = Get-Content -Raw -Encoding UTF8 $PromptFile

    Log "[READY] 항목 감지 -> 자비스 Claude Code 야간 개발 실행"
    # 비대화형 실행 (로그 저장). 필요 시 --max-turns 로 실행 한도 제한
    claude -p $PromptText --max-turns 40 2>&1 | Tee-Object -FilePath $LogFile -Append
    Log "=== 자비스 새벽 R&D 자동 개발 완료 ==="
} catch {
    Log "오류 발생: $($_.Exception.Message)"
    exit 1
}
