@echo off
chcp 65001 >NUL
REM Harnex frontend dev runner (Windows).
REM Streams Vite output directly to this console window.
REM Press Ctrl+C to stop the dev server. The window stays open afterwards
REM so you can read any error message before it disappears.

cd /d %~dp0

REM Note: avoid unescaped ')' inside this if-block — cmd would treat it as the
REM block terminator and silently abort the rest of the script (window closes).
if not exist node_modules (
  echo [dev-run] installing npm dependencies - first run, may take a minute...
  call npm install --no-audit --no-fund
  if errorlevel 1 (
    echo.
    echo [dev-run] npm install failed.
    pause
    exit /b 1
  )
)

echo.
echo ============================================================
echo  Harnex frontend dev server
echo  url   : http://localhost:18081
echo  stop  : Ctrl+C
echo ============================================================
echo.

REM Run npm in the foreground; output streams live to this window.
REM `call` is required because npm is a .cmd batch script — without it,
REM control would not return to this script and `pause` would never run.
call npm run dev

echo.
echo [dev-run] dev server exited (code %errorlevel%).
pause
