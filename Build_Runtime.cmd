@echo off
setlocal EnableExtensions
call "%~dp0scripts\python_entry.cmd" "%~dp0scripts\build_runtime.py" %*
exit /b %ERRORLEVEL%
