@echo off
call "%~dp0Launch.cmd" --permissions-only %*
exit /b %ERRORLEVEL%
