@echo off
setlocal

echo ==========================================
echo      VASATEYSEC - BUILD and INSTALL 🛠️
echo      (Builds APK, Install, Run)
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

:: 2. Build and Install
echo 🔨 Building and Installing Debug APK...
call gradlew.bat installDebug

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ❌ BUILD FAILED!
    echo Check the error logs above.
    pause
    exit /b 1
)

:: 3. Launch
echo.
echo ✅ Build and Install Complete!
echo 🚀 Launching App...
"%ADB_EXE%" shell monkey -p com.sriox.vasateysec -c android.intent.category.LAUNCHER 1 >nul 2>nul

echo.
pause
