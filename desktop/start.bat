@echo off
rem lan-projects 电脑端启动脚本
rem 共享的 Node 项目在 ..\web（package.json + server\），这里切过去再启动。
cd /d "%~dp0..\web" || (
    pause
    exit /b 1
)

if not exist "package.json" (
    pause
    exit /b 1
)

:: Kill any existing process on port 3000 to avoid EADDRINUSE
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":3000.*LISTENING" 2^>nul') do (
    taskkill /f /pid %%a >nul 2>nul
)
timeout /t 1 /nobreak >nul

if not exist "node_modules" (
    call npm install
)

node server\index.js --include-ws-fallback

pause
