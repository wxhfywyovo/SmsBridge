@echo off
setlocal
set JAR=%~dp0..\dist\SmsBridge.jar
if not exist "%JAR%" (
  echo SmsBridge.jar not found. Run desktop\build.ps1 first.
  pause
  exit /b 1
)
start "" javaw -jar "%JAR%"
