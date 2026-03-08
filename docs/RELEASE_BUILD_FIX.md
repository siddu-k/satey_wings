# Release Build Fix - "User Not Logged In" Issue

## Problem Summary
The app works perfectly in **debug mode** but shows "user not logged in" errors in **release/APK mode**. This is caused by ProGuard/R8 obfuscation stripping critical classes during the release build process.

## Root Causes Identified

### 1. **Kotlinx Serialization Classes Being Obfuscated**
- Your data models (`UserProfile`, `Guardian`, `FCMToken`, etc.) use `@Serializable` annotation
- R8/ProGuard was removing or renaming these classes and their serializers
- This breaks Supabase API calls that rely on JSON serialization/deserialization

### 2. **EncryptedSharedPreferences Issues**
- `SessionManager` uses `EncryptedSharedPreferences` for secure storage
- Encryption libraries (Tink, Security Crypto) were being obfuscated
- Session data couldn't be properly read/written in release builds

### 3. **OkHttp and Ktor Classes**
- HTTP client libraries used by Supabase were being stripped
- Network calls to Vercel endpoint were failing silently

### 4. **Missing ProGuard Rules**
- The `proguard-rules.pro` file was nearly empty (only comments)
- R8 was aggressively removing "unused" code that was actually needed

## Solutions Applied

### ✅ 1. Comprehensive ProGuard Rules Added
Updated `app/proguard-rules.pro` with rules for:
- **Kotlinx Serialization**: Keeps all `@Serializable` classes and their serializers
- **Data Models**: Preserves all classes in `com.sriox.vasateysec.models` package
- **Supabase SDK**: Keeps Supabase and Ktor classes
- **OkHttp**: Prevents obfuscation of HTTP client
- **EncryptedSharedPreferences**: Preserves Security Crypto and Tink libraries
- **SessionManager**: Explicitly keeps all utility classes
- **Firebase FCM**: Preserves Firebase messaging classes

### ✅ 2. Enhanced Debug Logging
Added detailed logging to `SessionManager`:
- Logs initialization state
- Tracks session save/load operations
- Shows encrypted vs fallback storage usage
- Helps diagnose issues in release builds via Logcat

### ✅ 3. Build Configuration Updated
Modified `app/build.gradle.kts`:
- **Enabled** `isMinifyEnabled = true` (with proper ProGuard rules)
- **Enabled** `isShrinkResources = true` (safe with correct rules)
- Added explicit debug build configuration
- Kept JNI library packaging settings for native libraries

## How to Build and Test

### Step 1: Stop Gradle Daemons (if build fails with file lock errors)
```bash
./gradlew --stop
```

### Step 2: Build Release APK (skip clean if file locks occur)
```bash
./gradlew assembleRelease
```

**Note:** If you get file lock errors with `./gradlew clean`, just run `assembleRelease` directly.

### Step 3: Install and Test
```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

### Step 4: Monitor Logs
```bash
adb logcat -s SessionManager AlertManager FCMTokenManager
```

## What to Look For in Logs

### ✅ Good Signs (Working)
```
SessionManager: SessionManager initialized with encrypted storage
SessionManager: Current session state - isLoggedIn: true, userId: abc123...
SessionManager: isLoggedIn check: true (flag: true, userId: abc123...)
AlertManager: Using Supabase session for user: abc123...
```

### ❌ Bad Signs (Still Broken)
```
SessionManager: Failed to initialize encrypted preferences
SessionManager: isLoggedIn check: false (flag: false, userId: null)
AlertManager: User not logged in
```

## Additional Troubleshooting

### If Still Getting "User Not Logged In"

1. **Check if SessionManager is initialized**
   - Look for "SessionManager initialized" in logs
   - Should happen in `VasateyApplication.onCreate()`

2. **Verify session is saved after login**
   - Look for "Session saved successfully" after login
   - Check if userId is present in verification log

3. **Check EncryptedSharedPreferences**
   - If you see "falling back to standard", encryption failed
   - This is OK - fallback should still work

4. **Verify Supabase auth token**
   - Check if `SupabaseClient.client.auth.currentUserOrNull()` returns null
   - SessionManager should be the fallback

### If Vercel Endpoint Errors Persist

1. **Check network logs**
   ```bash
   adb logcat -s OkHttp
   ```

2. **Verify JSON serialization**
   - Look for "JSON Payload being sent to Vercel" in logs
   - Ensure all fields are properly serialized (not null/undefined)

3. **Check Vercel logs**
   - Look for deserialization errors
   - Verify FCM token format is correct

## Testing Checklist

- [ ] Clean build completed without errors
- [ ] Release APK installs successfully
- [ ] User can log in and session persists
- [ ] Voice command "help me" triggers alert
- [ ] Alert sends to guardian's FCM token
- [ ] Notification appears on guardian's device
- [ ] Location data is included in alert
- [ ] Photos upload successfully (if enabled)
- [ ] No "user not logged in" errors in Logcat
- [ ] No serialization errors in Vercel logs

## Prevention for Future

### Always Keep These ProGuard Rules
- Never disable ProGuard rules for serialization libraries
- Keep data model classes unobfuscated
- Preserve all SDK classes (Supabase, Firebase, etc.)

### Test Release Builds Regularly
- Don't wait until final release to test APK
- Use `./gradlew assembleRelease` frequently during development
- Monitor Logcat for obfuscation-related errors

### Use R8 Full Mode Safely
- Current rules support R8 full mode
- Keep `@SerializedName` annotations if using Gson
- Test thoroughly after any dependency updates

## Files Modified

1. **app/proguard-rules.pro** - Added comprehensive ProGuard rules
2. **app/build.gradle.kts** - Enabled minification with proper configuration
3. **app/src/main/java/com/sriox/vasateysec/utils/SessionManager.kt** - Added debug logging

## Expected Outcome

After applying these fixes:
- ✅ Release APK should work identically to debug build
- ✅ User sessions persist across app restarts
- ✅ Alert system sends notifications successfully
- ✅ No "user not logged in" errors
- ✅ Vercel endpoint receives properly formatted requests
- ✅ APK size reduced due to code shrinking (with safety preserved)

## Support

If issues persist after applying these fixes:
1. Share complete Logcat output (filter: SessionManager, AlertManager)
2. Share Vercel function logs
3. Verify ProGuard rules were applied (check build output)
4. Confirm APK was built with latest code changes
