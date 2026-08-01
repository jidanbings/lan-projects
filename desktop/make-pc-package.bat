@echo off
rem ============================================================
rem  Package the PC-side server (web + start.bat) into a release
rem  zip at the project root:  lan-projects-pc-vX.Y.Z.zip
rem  The version is read from app\app\version.properties.
rem  Output stays INSIDE lan-projects (see DEVELOPER.md upload rule).
rem ============================================================
cd /d "%~dp0.." || exit /b 1

rem Read the current versionName from app\app\version.properties.
set "VERSION_NAME="
for /f "tokens=2 delims==" %%a in ('findstr /b "versionName" app\app\version.properties') do set "VERSION_NAME=%%a"
if not defined VERSION_NAME set "VERSION_NAME=unknown"

set "PKG_NAME=lan-projects-pc-v%VERSION_NAME%"
set "ZIP=%PKG_NAME%.zip"
set "STAGE=%TEMP%\%PKG_NAME%"

echo [PACKAGE] staging %PKG_NAME%
if exist "%STAGE%" rd /s /q "%STAGE%"
mkdir "%STAGE%"
xcopy /y /e /q "web\*" "%STAGE%\web\" >nul
copy /y "desktop\start.bat" "%STAGE%\start.bat" >nul

echo [PACKAGE] zipping -^> %ZIP%
powershell -NoProfile -Command "Compress-Archive -Path '%STAGE%' -DestinationPath '%ZIP%' -Force"

if exist "%ZIP%" (
    echo [OK] PC package generated: %ZIP%
) else (
    echo [FAILED] zip was not created.
)
rd /s /q "%STAGE%"
pause
