@echo off
setlocal
if not defined JAVA_HOME (
  echo Set JAVA_HOME to a Java 17 or later runtime.
  exit /b 2
)
"%JAVA_HOME%\bin\java.exe" -cp "%~dp0lib\*" dev.monaka.tracking.hil.CorePocHilMain %*
