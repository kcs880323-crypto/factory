@echo off
rem Partners Manager uninstaller.
rem One line on purpose: this file deletes its own folder, so cmd must not read further lines afterwards.
cd /d "%TEMP%" & powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall.ps1" & exit /b
