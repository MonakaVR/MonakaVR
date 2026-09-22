@echo off
setlocal

cd /d "%~dp0.."
if errorlevel 1 (
  echo ERROR: Could not enter the MonakaVR repository root. 1>&2
  exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run_rotation_only_steamvr_hil.ps1" %*
set "MONAKA_HIL_EXIT=%ERRORLEVEL%"
endlocal & exit /b %MONAKA_HIL_EXIT%
