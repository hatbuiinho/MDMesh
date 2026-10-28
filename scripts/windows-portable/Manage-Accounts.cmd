@echo off
call "%~dp0Launch.cmd" --manage-accounts %*
exit /b %ERRORLEVEL%
