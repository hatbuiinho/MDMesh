@echo off
setlocal DisableDelayedExpansion
chcp 65001 >nul
pushd "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Setup.ps1"
if errorlevel 1 goto failed
set "ADB=%~dp0tools\platform-tools\adb.exe"
"%~dp0tools\python\python.exe" -X utf8 -B "%~dp0app\scripts\enroll-device-adb.py" --env-file "%~dp0.env" %*
set "RESULT=%ERRORLEVEL%"
goto done
:failed
set "RESULT=1"
echo Setup failed. Check the message above and your internet connection.
:done
echo.
if not "%RESULT%"=="0" echo Operation failed with exit code %RESULT%.
popd
pause
exit /b %RESULT%
