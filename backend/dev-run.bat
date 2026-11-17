@echo off
chcp 65001 >NUL
REM Harnex backend dev runner (Windows).
REM Streams Maven / Spring Boot output directly to this console window.
REM Press Ctrl+C to stop. The window stays open afterwards so you can
REM read any startup failure before it disappears.

cd /d %~dp0

set PORT=8088

echo.
echo ============================================================
echo  Harnex backend dev server
echo  url   : http://localhost:%PORT%
echo  stop  : Ctrl+C
echo ============================================================
echo.

REM Check if port is already in use and show the occupying process.
for /f "tokens=5" %%a in ('netstat -ano ^| findstr "LISTENING" ^| findstr ":%PORT% "') do (
    echo [dev-run] ERROR: Port %PORT% is already in use!
    echo [dev-run] Occupying process:
    tasklist /FI "PID eq %%a" /FO TABLE /NH
    echo.
    echo [dev-run] Kill it with: taskkill /F /PID %%a
    echo.
    pause
    exit /b 1
)

REM Clean build to avoid stale class files, then run.
call mvn clean -DskipTests spring-boot:run

echo.
echo [dev-run] backend exited (code %errorlevel%).
pause
