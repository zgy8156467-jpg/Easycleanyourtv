@echo off
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0restore-tvhome.ps1" %*
echo.
pause
