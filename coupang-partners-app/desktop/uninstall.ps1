# 파트너스 매니저 지우기: 서버를 끄고 바로가기와 설치 폴더를 지운다.
# 키·링크 보관함(앱 창의 브라우저 데이터)은 물어본 뒤에만 지운다.
$ErrorActionPreference = 'Continue'
$base = Join-Path $env:LOCALAPPDATA 'PartnersManager'
$name = '파트너스 매니저'
try { Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8790/__quit' -Method Post -TimeoutSec 2 | Out-Null; Start-Sleep -Milliseconds 500 } catch { }
foreach ($dir in @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))) {
  $p = Join-Path $dir "$name.lnk"
  if (Test-Path $p) { Remove-Item $p -Force }
}
$app = Join-Path $base 'app'
if (Test-Path $app) {
  try { Remove-Item $app -Recurse -Force -ErrorAction Stop; Write-Host '바로가기와 프로그램 파일을 지웠어요.' }
  catch { Write-Host '프로그램 파일 일부를 지우지 못했어요. 앱 창과 "Partners Manager server" 창을 닫고 다시 해 주세요.' -ForegroundColor Yellow }
} else { Write-Host '바로가기를 지웠어요.' }
$data = Join-Path $base 'browser'
if (Test-Path $data) {
  $a = Read-Host '저장된 API 키와 링크 보관함(브라우저 데이터)도 지울까요? 지우면 되돌릴 수 없어요. (y/N)'
  if ($a -eq 'y' -or $a -eq 'Y') {
    Remove-Item $data -Recurse -Force
    Write-Host '키와 보관함도 지웠어요.'
  } else {
    Write-Host "키와 보관함은 남겨 뒀어요: $data (다시 설치하면 그대로 보여요)"
  }
}
Read-Host '끝났어요. Enter를 누르면 닫혀요'
