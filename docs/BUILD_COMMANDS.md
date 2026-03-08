# Quick Build Commands Reference

## Clean Build (Always do this first after changes)
```bash
./gradlew clean
```

## Build Debug APK (for testing)
```bash
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

## Build Release APK (production)
```bash
./gradlew assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk`

## Install APK to Device
```bash
# Install debug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Install release
adb install -r app/build/outputs/apk/release/app-release.apk
```

## View Logs (Filtered)
```bash
# All app logs
adb logcat -s SessionManager AlertManager FCMTokenManager VasateyFCMService

# Just SessionManager
adb logcat -s SessionManager

# Just network/OkHttp
adb logcat -s OkHttp

# Clear logs first, then monitor
adb logcat -c && adb logcat -s SessionManager AlertManager
```

## Uninstall App
```bash
adb uninstall com.sriox.vasateysec
```

## Check Connected Devices
```bash
adb devices
```

## Full Clean Rebuild (if having issues)
```bash
./gradlew clean
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Verify ProGuard Mapping (Advanced)
```bash
# Check what was kept/removed
cat app/build/outputs/mapping/release/mapping.txt
```
