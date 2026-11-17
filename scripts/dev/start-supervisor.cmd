@echo off
chcp 65001 >nul
REM Supervisor 模式：单 JVM、单 active workspace + 前端 dist
REM 双击或：scripts\dev\start-supervisor.cmd [--config <dir>] [--runtime-domain-dir <dir>] ...
REM 等价：npm run dev:supervisor
setlocal
cd /d "%~dp0..\.." || exit /b 1
node "scripts\dev\dev.mjs" --supervisor %*
set "HARNEX_WRAPPER_EXIT=%ERRORLEVEL%"
echo.
echo (按任意键关闭窗口)
if not defined CI if not defined HARNEX_NO_PAUSE pause >nul
endlocal & exit /b %HARNEX_WRAPPER_EXIT%
