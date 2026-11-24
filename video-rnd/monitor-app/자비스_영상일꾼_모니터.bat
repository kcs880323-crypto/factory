@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 자비스 영상일꾼 · 퀄리티 R&D 모니터를 시작합니다...
python app.py
if errorlevel 1 (
    py -3 app.py
)
pause
