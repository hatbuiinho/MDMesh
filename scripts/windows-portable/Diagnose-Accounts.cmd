@echo off
call "%~dp0Launch.cmd" --diagnose-accounts %*
exit /b %ERRORLEVEL%
