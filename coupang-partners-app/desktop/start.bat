@echo off
rem Partners Manager launcher: starts the local server (127.0.0.1 only) and opens the app window.
setlocal
cd /d "%~dp0"
set "PORT=8790"
set "URL=http://127.0.0.1:%PORT%/"
set "PROFILE=%LOCALAPPDATA%\PartnersManager\browser"
set "SERVER=%~dp0server.cjs"
rem Run from outside the install folder so updates/uninstall can replace it while the app is open.
cd /d "%LOCALAPPDATA%"

where node >nul 2>nul
if errorlevel 1 goto nonode

curl.exe -s -m 2 "%URL%__health" | find "partners-manager" >nul
if not errorlevel 1 goto open

start "Partners Manager server - close this window to stop" /min node "%SERVER%"
for /l %%i in (1,1,30) do (
  timeout /t 1 /nobreak >nul
  curl.exe -s -m 1 "%URL%__health" | find "partners-manager" >nul && goto open
)
echo The server did not start. Check the "Partners Manager server" window for the reason.
pause
exit /b 1

:open
set "EDGE=%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"
if not exist "%EDGE%" set "EDGE=%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"
if exist "%EDGE%" goto edge
set "CHROME=%ProgramFiles%\Google\Chrome\Application\chrome.exe"
if not exist "%CHROME%" set "CHROME=%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe"
if exist "%CHROME%" goto chrome
start "" "%URL%"
exit /b 0

:edge
start "" "%EDGE%" --app="%URL%" --user-data-dir="%PROFILE%" --no-first-run --no-default-browser-check --window-size=520,920
exit /b 0

:chrome
start "" "%CHROME%" --app="%URL%" --user-data-dir="%PROFILE%" --no-first-run --no-default-browser-check --window-size=520,920
exit /b 0

:nonode
echo Node.js is not installed. The download page will open.
echo Install the LTS version, then run Partners Manager again.
start "" "https://nodejs.org/"
pause
exit /b 1
