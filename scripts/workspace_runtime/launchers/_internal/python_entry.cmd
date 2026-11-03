@echo off
setlocal
if defined MONAKA_PYTHON goto configured
where python.exe >nul 2>nul
if not errorlevel 1 goto python
where py.exe >nul 2>nul
if not errorlevel 1 goto py
echo Python 3 is required. Set MONAKA_PYTHON or install Python on PATH. 1>&2
exit /b 3
:configured
"%MONAKA_PYTHON%" -B -X utf8 %*
exit /b %errorlevel%
:python
python.exe -B -X utf8 %*
exit /b %errorlevel%
:py
py.exe -3 -B -X utf8 %*
exit /b %errorlevel%
