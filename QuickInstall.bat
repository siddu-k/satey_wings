@echo off
setlocal

echo ==========================================
echo      VASATEYSEC - QUICK INSTALL ⚡
echo      (Installs existing APK only)
echo ==========================================
echo.

:: 1. Check for ADB
where adb >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo ⚠️ ADB not found in PATH!
    if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
        set "ADB_EXE=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
    ) else if exist "%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe" (
        set "ADB_EXE=%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    ) else (
        echo ❌ ADB not found. Check Android SDK.
        pause
        exit /b 1
    )
) else (
    set "ADB_EXE=adb"
)

:: 2. Check for APK
set "APK_PATH=app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK_PATH%" (
    echo ❌ APK NOT FOUND!
    echo.
    echo File not found: %APK_PATH%
    echo.
    echo You must BUILD the app at least once before using Quick Install.
    echo Please run 'InstallAndRun.bat' first.
    pause
    exit /b 1
)

:: 3. Install
echo 📦 Found APK! Installing to device...
"%ADB_EXE%" install -r "%APK_PATH%"

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ❌ INSTALL FAILED!
    echo Check if device is connected and authorized.
) else (
    echo.
    echo ✅ Install Complete!
    echo 🚀 Launching App...
    "%ADB_EXE%" shell monkey -p com.sriox.vasateysec -c android.intent.category.LAUNCHER 1 >nul 2>nul
)

echo.
pause
