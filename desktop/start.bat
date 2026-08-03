@echo off
rem lan-projects 电脑端启动脚本
rem 共享的 Node 项目在 web（package.json + server\），这里切过去再启动。
rem 兼容两种布局：
rem   1) 仓库内：desktop\start.bat，web 在 ..\web
rem   2) 发布包内：start.bat 与 web\ 同级
if exist "%~dp0web" (
    cd /d "%~dp0web"
) else (
    cd /d "%~dp0..\web"
)
if errorlevel 1 (
    echo [ERROR] 找不到 web 目录，请确认 start.bat 与 web 的相对位置。
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
