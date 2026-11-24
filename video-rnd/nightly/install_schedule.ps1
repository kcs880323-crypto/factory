# 자비스 메인 PC에서 한 번만 실행: 매일 새벽 03:30 자동 개발 작업 스케줄러 등록
param(
    [string]$RepoPath = "$env:USERPROFILE\factory",
    [string]$RunTime = "03:30"
)

$ScriptPath = Join-Path $RepoPath "video-rnd\nightly\run_nightly_rnd.ps1"
if (-not (Test-Path $ScriptPath)) {
    Write-Error "스크립트를 찾을 수 없습니다: $ScriptPath"
    exit 1
}

$TaskName = "Jarvis_Nightly_RnD_Update"
$Action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$ScriptPath`" -RepoPath `"$RepoPath`""
$Trigger = New-ScheduledTaskTrigger -Daily -At $RunTime
$Settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 80)

Register-ScheduledTask -TaskName $TaskName -Action $Action -Trigger $Trigger -Settings $Settings -Force
Write-Host "등록 완료! 작업 이름: $TaskName (매일 $RunTime 자동 실행, 최대 80분 제한)"
Write-Host "확인 명령: Get-ScheduledTask -TaskName $TaskName"
