@echo off
setlocal EnableExtensions
set "PYTHON="
set "PYTHON_FLAGS="
if defined MONAKA_PYTHON (
  if exist "%MONAKA_PYTHON%" (
    "%MONAKA_PYTHON%" -c "import sys; sys.exit(sys.version_info[0] != 3)" >nul 2>&1
    if not errorlevel 1 set "PYTHON=%MONAKA_PYTHON%"
  )
) else (
  for %%P in (python.exe) do if not "%%~$PATH:P"=="" (
    "%%~$PATH:P" -c "import sys; sys.exit(sys.version_info[0] != 3)" >nul 2>&1
    if not errorlevel 1 set "PYTHON=%%~$PATH:P"
  )
  if not defined PYTHON (
    for %%P in (py.exe) do if not "%%~$PATH:P"=="" (
      "%%~$PATH:P" -3 -c "import sys; sys.exit(sys.version_info[0] != 3)" >nul 2>&1
      if not errorlevel 1 (
        set "PYTHON=%%~$PATH:P"
        set "PYTHON_FLAGS=-3"
      )
    )
  )
)
if not defined PYTHON (
  echo [ERROR] Python 3 runtime was not found. Set MONAKA_PYTHON to python.exe, or add Python to PATH.
  exit /b 2
)
"%PYTHON%" %PYTHON_FLAGS% -B -X utf8 %*
exit /b %ERRORLEVEL%
