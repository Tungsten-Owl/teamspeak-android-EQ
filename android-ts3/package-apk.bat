@echo off
rem Double-click entry point for package-apk.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0package-apk.ps1" %*
set EXITCODE=%ERRORLEVEL%
echo.
if not "%EXITCODE%"=="0" (
    echo Build failed with exit code %EXITCODE%.
) else (
    echo Build finished.
)
pause
exit /b %EXITCODE%
