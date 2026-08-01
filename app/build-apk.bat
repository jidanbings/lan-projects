@echo off
rem ============================================================
rem  Rebuild lan-projects Android APK
rem  Requires: Java 17, Android SDK (D:\Android), Gradle (D:\Android)
rem
rem  Every run auto-bumps the version in app\build.gradle:
rem    versionCode +1        (the only number Android uses to detect an
rem                           update - it must keep rising, otherwise the
rem                           new APK will not install over the old one)
rem    versionName x.y.z     -> x.y.z+1   (the visible version string)
rem
rem  Usage:  build-apk.bat          -> debug APK (development only)
rem          build-apk.bat release  -> signed release APK (for distribution)
rem ============================================================
cd /d "%~dp0" || exit /b 1

if not exist "local.properties" (
    echo sdk.dir=D:\Android> local.properties
)

rem ------------------------------------------------------------
rem  Build mode. "release" produces the SIGNED release APK (requires
rem  app\keystore.properties with the private keystore, see the
rem  developer docs). The default "debug" is signed with the local
rem  debug key and is only for development - it is refused by the
rem  runtime signature check on release builds.
rem ------------------------------------------------------------
set "MODE=debug"
if /i "%~1"=="release" set "MODE=release"

rem ------------------------------------------------------------
rem  Auto-bump the version before building.
rem  Version lives in app\version.properties (two simple lines), so
rem  bumping = read two values + write two lines back. versionCode is
rem  the only number Android uses to detect an update; it must keep
rem  rising or the new APK will not install over the old one.
rem ------------------------------------------------------------
set "VERSION_FILE=app\version.properties"
if not exist "%VERSION_FILE%" (
    echo versionCode=1> "%VERSION_FILE%"
    echo versionName=1.0.0>> "%VERSION_FILE%"
)

set "VERSION_CODE="
for /f "tokens=2 delims==" %%a in ('findstr /b "versionCode" "%VERSION_FILE%"') do set "VERSION_CODE=%%a"
set "VERSION_NAME="
for /f "tokens=2 delims==" %%a in ('findstr /b "versionName" "%VERSION_FILE%"') do set "VERSION_NAME=%%a"

if not defined VERSION_CODE goto :sync

set /a VERSION_CODE+=1
for /f "tokens=1-3 delims=." %%a in ("%VERSION_NAME%") do (
    set "VER_MAJOR=%%a"
    set "VER_MINOR=%%b"
    set "VER_PATCH=%%c"
)
set /a VER_PATCH+=1
set "VERSION_NAME=%VER_MAJOR%.%VER_MINOR%.%VER_PATCH%"

> "%VERSION_FILE%" (
    echo versionCode=%VERSION_CODE%
    echo versionName=%VERSION_NAME%
)
echo [VERSION] bumped to %VERSION_NAME% (versionCode %VERSION_CODE%)

:sync
rem Sync the web project (frontend + server) into the bundled assets.
rem The shared code lives in ..\web (public\ + server\). node_modules is not
rem part of the repo and must be preserved, so only public\ and server\ are
rem copied. Any edit to the web code is picked up automatically before build.
set ASSETS=app\src\main\assets\nodejs-project
echo [SYNC] ..\web\public -^> %ASSETS%\public
xcopy /y /e /q "..\web\public\*" "%ASSETS%\public\" >nul
echo [SYNC] ..\web\server -^> %ASSETS%\server
xcopy /y /e /q "..\web\server\*" "%ASSETS%\server\" >nul

call "D:\Android\gradle-8.11.1\bin\gradle.bat" assemble%MODE%
if errorlevel 1 (
    echo.
    echo [BUILD FAILED] See errors above.
    pause
    exit /b 1
)

rem Copy the APK to the project root with the new version number.
set APK_FILE=app\build\outputs\apk\%MODE%\app-%MODE%.apk
if exist "%APK_FILE%" (
    if not defined VERSION_NAME set "VERSION_NAME=unknown"
    copy /y "%APK_FILE%" "..\lan-projects-v%VERSION_NAME%-%MODE%.apk" >nul
    echo.
    echo [OK] APK generated: ..\lan-projects-v%VERSION_NAME%-%MODE%.apk
)

rem Clean the Gradle build intermediates so the project folder stays small.
rem The APK is already copied out; the build dir is regenerated next build.
rd /s /q "app\build" 2>nul
echo [CLEAN] Removed build intermediates (app\app\build)
pause
