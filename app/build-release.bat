@echo off
rem ============================================================
rem  One-click wrapper: build the SIGNED release APK.
rem  Equivalent to running "build-apk.bat release".
rem  The release APK is copied to the project root as
rem  lan-projects-vX.Y.Z-release.apk.
rem
rem  Note: build-apk.bat is called by its FULL path (%~dp0...) -
rem  a relative "call build-apk.bat" can fail to find the file even
rem  when it sits in the same directory.
rem ============================================================
call "%~dp0build-apk.bat" release
