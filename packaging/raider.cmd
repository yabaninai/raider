@echo off
setlocal
set "HERE=%~dp0"
if "%~1"=="--version" (
  if not exist "%HERE%version" exit /b 21
  set /p RAIDER_VERSION=<"%HERE%version"
  echo raider %RAIDER_VERSION%
  exit /b 0
)
if not exist "%HERE%raider-cli.jar" exit /b 21
java -jar "%HERE%raider-cli.jar" %*
exit /b %ERRORLEVEL%
