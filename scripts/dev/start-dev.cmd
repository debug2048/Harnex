@echo off
chcp 65001 >nul
REM 一键开发模式：后端 jar + Vite Dev (HMR)
REM 双击或：scripts\dev\start-dev.cmd [--config <dir>] [--data-dir <dir>] [--no-browser] ...
REM 等价：npm run dev  /  node scripts\dev\dev.mjs %*
setlocal
cd /d "%~dp0..\.." || exit /b 1
node "scripts\dev\dev.mjs" %*
set "HARNEX_WRAPPER_EXIT=%ERRORLEVEL%"
echo.
echo (按任意键关闭窗口 / 进程已通过 Ctrl+C 停止)
if not defined CI if not defined HARNEX_NO_PAUSE pause >nul
endlocal & exit /b %HARNEX_WRAPPER_EXIT%
