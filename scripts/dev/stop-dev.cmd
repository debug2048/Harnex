@echo off
chcp 65001 >nul
REM 停止 scripts\dev\dev.mjs 启动的所有进程（读 %USERPROFILE%\.harnex\dev.pids）
setlocal
cd /d "%~dp0..\.." || exit /b 1
node "scripts\dev\dev.mjs" --stop %*
set "HARNEX_WRAPPER_EXIT=%ERRORLEVEL%"
endlocal & exit /b %HARNEX_WRAPPER_EXIT%
