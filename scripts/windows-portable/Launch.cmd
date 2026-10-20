@echo off
setlocal DisableDelayedExpansion
chcp 65001 >nul
pushd "%~dp0"
if not exist "%~dp0app\scripts\enroll-device-adb.py" goto incomplete
if not exist "%~dp0app\scripts\publish-release.py" goto incomplete
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Setup.ps1"
if errorlevel 1 goto failed
set "ADB=%~dp0tools\platform-tools\adb.exe"
"%~dp0tools\python\python.exe" -X utf8 -B "%~dp0app\scripts\enroll-device-adb.py" --env-file "%~dp0.env" %*
set "RESULT=%ERRORLEVEL%"
goto done
:incomplete
echo This folder is incomplete: app\scripts is missing.
echo Extract the complete MDMesh-Enroll-Windows.zip, then run MDMesh-Enroll\Enroll.cmd.
echo The repository scripts\windows-portable folder contains templates only.
set "RESULT=2"
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
