@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0..\.." || exit /b 1
node "scripts\build\build-dist.mjs" %*
set "BUILD_EXIT=%ERRORLEVEL%"
echo.
if not defined CI if not defined HARNEX_NO_PAUSE if not defined HARNEX_BUILD_NO_PAUSE pause
endlocal & exit /b %BUILD_EXIT%
