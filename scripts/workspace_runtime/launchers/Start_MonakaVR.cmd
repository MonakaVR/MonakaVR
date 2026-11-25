@echo off
setlocal EnableExtensions
call "%~dp0_internal\python_entry.cmd" "%~dp0_internal\monakavr.py" %*
exit /b %ERRORLEVEL%
