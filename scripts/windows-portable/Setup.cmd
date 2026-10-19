@echo off
setlocal DisableDelayedExpansion
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Setup.ps1"
set "RESULT=%ERRORLEVEL%"
pause
exit /b %RESULT%
