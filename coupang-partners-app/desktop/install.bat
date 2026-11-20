@echo off
rem Partners Manager installer: copies files to %LOCALAPPDATA%\PartnersManager\app and makes a desktop shortcut.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1"
pause
