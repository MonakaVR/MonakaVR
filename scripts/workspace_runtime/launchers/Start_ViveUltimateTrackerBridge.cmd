@echo off
setlocal EnableExtensions
if not "%~1"=="" if /I not "%~1"=="--check" exit /b 2
if not "%~2"=="" exit /b 2
if /I "%~1"=="--check" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0_internal\vive.ps1" -Check
) else (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0_internal\vive.ps1"
)
exit /b %ERRORLEVEL%
