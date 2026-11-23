# 파트너스 매니저 설치: 이 폴더의 파일을 %LOCALAPPDATA%\PartnersManager\app 으로 복사하고
# 바탕화면·시작 메뉴에 바로가기를 만든다. 관리자 권한이 필요 없다. 키·링크(브라우저 데이터)는 건드리지 않는다.
$ErrorActionPreference = 'Stop'
$src = Split-Path -Parent $MyInvocation.MyCommand.Path
$base = Join-Path $env:LOCALAPPDATA 'PartnersManager'
$app = Join-Path $base 'app'
$name = '파트너스 매니저'

Write-Host ''
Write-Host "== $name 설치 ==" -ForegroundColor Cyan

if (-not (Test-Path (Join-Path $src 'server.cjs')) -or -not (Test-Path (Join-Path $src 'web\index.html'))) {
  Write-Host '설치 파일이 모자라요. 압축 파일을 먼저 "압축 풀기" 한 뒤, 풀린 폴더 안의 install.bat 을 실행해 주세요.' -ForegroundColor Red
  exit 1
}

# 1) Node.js 확인 (18 이상)
$node = Get-Command node -ErrorAction SilentlyContinue
if (-not $node) {
  Write-Host 'Node.js가 없어요. 다운로드 페이지를 열게요. LTS 버전을 설치한 뒤 install.bat 을 다시 실행해 주세요.' -ForegroundColor Yellow
  Start-Process 'https://nodejs.org/'
  exit 1
}
$ver = (& node -v).Trim().TrimStart('v')
if ([int]($ver.Split('.')[0]) -lt 18) {
  Write-Host "Node.js $ver 는 너무 오래됐어요 (18 이상 필요). https://nodejs.org 에서 LTS를 설치해 주세요." -ForegroundColor Yellow
  Start-Process 'https://nodejs.org/'
  exit 1
}
Write-Host "Node.js $ver 확인"

# 2) 켜져 있는 서버 끄기 (업데이트할 때)
try { Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8790/__quit' -Method Post -TimeoutSec 2 | Out-Null; Start-Sleep -Milliseconds 500 } catch { }

# 3) 파일 복사 (같은 폴더에서 다시 실행한 경우는 건너뜀)
New-Item -ItemType Directory -Force -Path $base | Out-Null
if ((Resolve-Path $src).Path.TrimEnd('\') -ne $app.TrimEnd('\')) {
  try {
    if (Test-Path $app) { Remove-Item $app -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $app | Out-Null
    Copy-Item -Path (Join-Path $src '*') -Destination $app -Recurse -Force
  } catch {
    Write-Host '설치 폴더를 바꾸지 못했어요. 파트너스 매니저 앱 창과 "Partners Manager server" 창을 모두 닫고 install.bat 을 다시 실행해 주세요.' -ForegroundColor Red
    Write-Host $_.Exception.Message
    exit 1
  }
}
Write-Host "설치 위치: $app"

# 4) 바로가기 (바탕화면·시작 메뉴)
$ws = New-Object -ComObject WScript.Shell
$places = @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))
foreach ($dir in $places) {
  if (-not $dir) { continue }
  $lnk = $ws.CreateShortcut((Join-Path $dir "$name.lnk"))
  $lnk.TargetPath = Join-Path $app 'start.bat'
  $lnk.WorkingDirectory = $app
  $lnk.IconLocation = (Join-Path $app 'icon.ico') + ',0'
  $lnk.WindowStyle = 7
  $lnk.Description = '쿠팡 파트너스·네이버 API를 쓰는 개인용 운영 도구'
  $lnk.Save()
}
Write-Host "바탕화면과 시작 메뉴에 '$name' 바로가기를 만들었어요."

# 5) 바로 실행
Start-Process -FilePath (Join-Path $app 'start.bat') -WorkingDirectory $app -WindowStyle Minimized
Write-Host '앱 창을 열어요. 설정 탭에서 쿠팡 파트너스 키와 네이버 API HUB 키를 넣어 주세요.' -ForegroundColor Green
