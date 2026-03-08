# Location Tracking Debug Guide

## Overview
This guide helps you debug why guardian location tracking isn't working when a guardian loads the tracker.

## The Complete Flow

```
1. Guardian opens GuardianMapActivity
   ↓
2. Calls LiveLocationHelper.requestLiveLocations()
   ↓
3. Supabase Edge Function sends FCM notifications
   ↓
4. User's device receives FCM (VasateyFCMService)
   ↓
5. LiveLocationHelper.handleLocationRequest() is called
   ↓
6. Gets user's current location
   ↓
7. Updates live_locations table in Supabase
   ↓
8. Guardian fetches locations from live_locations table
   ↓
9. Displays markers on map
```

## Common Issues & Solutions

### Issue 1: No FCM Token Registered
**Symptom:** User never receives the location request notification
**Check:** Look for this log in GuardianMap:
```
⚠️ User [user_id] has NO active FCM tokens!
```

**Solution:**
- User needs to open the app at least once
- FCM token is registered in `FCMTokenManager.updateFCMToken()`
- Check `fcm_tokens` table in Supabase to verify token exists

**Test:**
```sql
SELECT * FROM fcm_tokens WHERE user_id = '[user_id]' AND is_active = true;
```

---

### Issue 2: Location Permission Not Granted
**Symptom:** FCM received but location not updated
**Check:** Look for this log in LiveLocationHelper:
```
❌ CRITICAL: Location permission NOT granted!
```

**Solution:**
- User must grant location permission
- Go to: Settings → Apps → Vasatey → Permissions → Location → Allow all the time (or While using the app)

**Test:**
- Open the user's app
- Trigger a location request
- Check if permission dialog appears

---

### Issue 3: Location Services Disabled
**Symptom:** Permission granted but location still null
**Check:** Look for this log in LocationManager:
```
Location services are DISABLED on device
```

**Solution:**
- User must enable GPS/Location services
- Go to: Settings → Location → Turn ON

**Test:**
- Check device location settings
- Enable High Accuracy mode

---

### Issue 4: App Killed/Background Restricted
**Symptom:** FCM not waking up the app
**Check:** FCM notification not appearing at all

**Solution:**
- Disable battery optimization for the app
- Go to: Settings → Apps → Vasatey → Battery → Unrestricted
- Some manufacturers (Xiaomi, Huawei, etc.) have aggressive battery savers

**Test:**
- Kill the app completely
- Send a test FCM notification
- Check if notification appears

---

### Issue 5: User Not Logged In
**Symptom:** Location obtained but not saved to database
**Check:** Look for this log in LiveLocationHelper:
```
❌ CRITICAL: User not logged in!
```

**Solution:**
- User must be logged in
- Check if session expired
- User needs to re-login

**Test:**
```kotlin
val currentUser = SupabaseClient.client.auth.currentUserOrNull()
Log.d("Test", "User logged in: ${currentUser != null}")
```

---

### Issue 6: Supabase Edge Function Not Working
**Symptom:** No FCM notifications sent at all
**Check:** Look for error in Supabase function logs

**Solution:**
- Check Supabase Edge Function is deployed
- Verify FCM_SERVER_KEY is set in Supabase secrets
- Check function logs in Supabase dashboard

**Test:**
```bash
# Test the edge function directly
curl -X POST https://acgsmcxmesvsftzugeik.supabase.co/functions/v1/request-live-locations \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_ANON_KEY" \
  -d '{"guardian_email": "guardian@example.com"}'
```

---

### Issue 7: Timing Issue (8 seconds not enough)
**Symptom:** Some locations received, but not all
**Check:** Look for this log:
```
⚠️ Missing locations for X users
```

**Solution:**
- Increase wait time in GuardianMapActivity
- Currently set to 8 seconds, try 15 seconds
- Consider implementing real-time updates instead

**Fix:**
```kotlin
// In GuardianMapActivity.kt line 266
kotlinx.coroutines.delay(15000) // Increase from 8000 to 15000
```

---

## Step-by-Step Debugging Process

### Step 1: Check Guardian Side
1. Open GuardianMapActivity
2. Check logcat for tag `GuardianMap`
3. Look for:
   ```
   Found X users who added me as guardian
   🔍 Verifying FCM tokens...
   ✅ User [id] has X active token(s)
   📍 Requesting live locations...
   ```

### Step 2: Check Supabase Edge Function
1. Open Supabase Dashboard
2. Go to Edge Functions → request-live-locations
3. Check logs for:
   ```
   📍 Live location request from guardian: [email]
   📍 Found X users to request location from
   ✅ Location request sent to token: ...
   ```

### Step 3: Check User Side (Receiving Device)
1. Open logcat for user's device
2. Filter by tag `VasateyFCMService`
3. Look for:
   ```
   Message received from: ...
   Message data payload: {type=location_request, guardianEmail=...}
   ```

### Step 4: Check Location Request Handler
1. Filter logcat by tag `LiveLocationHelper`
2. Look for:
   ```
   📍 LOCATION REQUEST RECEIVED
   Guardian Email: [email]
   📍 Step 1: Checking location permissions...
   ✅ Location permission granted
   📍 Step 2: Getting current location...
   ✅ Location obtained in XXXms
   📍 Step 3: Getting current user...
   ✅ User authenticated
   📍 Step 4: Updating live location in database...
   ✅ Live location updated successfully
   ```

### Step 5: Check Database
1. Open Supabase Dashboard
2. Go to Table Editor → live_locations
3. Check if new records were inserted
4. Verify timestamp is recent (within last few seconds)

### Step 6: Check Guardian Fetch
1. Back to guardian's logcat
2. Filter by tag `LiveLocationHelper`
3. Look for:
   ```
   📍 FETCHING LIVE LOCATIONS
   Requesting locations for X users
   ✅ Query returned X total records
   ✅ Found location for user: [id]
   📍 FETCH COMPLETE: X/X locations found
   ```

---

## Quick Diagnostic Commands

### Check if user has FCM token:
```sql
SELECT user_id, token, device_name, is_active, created_at 
FROM fcm_tokens 
WHERE user_id = '[USER_ID]' 
ORDER BY created_at DESC;
```

### Check guardian relationships:
```sql
SELECT * FROM guardians 
WHERE guardian_email = '[GUARDIAN_EMAIL]';
```

### Check live locations:
```sql
SELECT * FROM live_locations 
ORDER BY updated_at DESC 
LIMIT 10;
```

### Check if location is recent:
```sql
SELECT 
  user_id, 
  latitude, 
  longitude, 
  accuracy,
  updated_at,
  EXTRACT(EPOCH FROM (NOW() - updated_at)) as seconds_ago
FROM live_locations 
WHERE user_id = '[USER_ID]';
```

---

## Testing Checklist

### Pre-requisites (User Device):
- [ ] App installed and opened at least once
- [ ] User is logged in
- [ ] Location permission granted (Allow all the time)
- [ ] Location services enabled (GPS ON)
- [ ] Battery optimization disabled
- [ ] Internet connection active
- [ ] FCM token registered (check database)

### Pre-requisites (Guardian Device):
- [ ] App installed and opened
- [ ] Guardian is logged in
- [ ] Internet connection active
- [ ] At least one user has added them as guardian

### Pre-requisites (Backend):
- [ ] Supabase Edge Function deployed
- [ ] FCM_SERVER_KEY configured in Supabase secrets
- [ ] Database tables exist (guardians, fcm_tokens, live_locations)

### Test Procedure:
1. [ ] Open user's app (keep it open or in background)
2. [ ] Open guardian's app
3. [ ] Navigate to Track/Map screen
4. [ ] Wait for "Requesting live locations..." toast
5. [ ] Wait 8 seconds
6. [ ] Check if green markers appear on map
7. [ ] Check logcat on both devices
8. [ ] Check Supabase database for new records

---

## Advanced Debugging

### Enable Verbose Logging
All logs are already enabled with detailed information. Filter by these tags:
- `LiveLocationHelper` - Main location tracking logic
- `LocationManager` - Location fetching logic
- `VasateyFCMService` - FCM message handling
- `GuardianMap` - Guardian map activity

### Monitor Network Requests
Use Android Studio's Network Profiler to monitor:
- Supabase API calls
- FCM requests
- Edge Function calls

### Test FCM Directly
Send a test FCM notification using curl:
```bash
curl -X POST https://fcm.googleapis.com/fcm/send \
  -H "Content-Type: application/json" \
  -H "Authorization: key=YOUR_FCM_SERVER_KEY" \
  -d '{
    "to": "USER_FCM_TOKEN",
    "data": {
      "type": "location_request",
      "guardianEmail": "test@example.com"
    },
    "priority": "high"
  }'
```

---

## Known Limitations

1. **8-second timeout**: May not be enough for devices in deep sleep
2. **No retry mechanism**: If location fails, no automatic retry
3. **No real-time updates**: Guardian must manually refresh
4. **Battery optimization**: Some devices aggressively kill background apps
5. **Manufacturer restrictions**: Xiaomi, Huawei, OnePlus have strict background restrictions

---

## Recommended Improvements

1. **Increase timeout to 15 seconds**
2. **Add retry mechanism** (try 2-3 times if location fails)
3. **Implement real-time updates** using Supabase Realtime
4. **Add user feedback** (show notification when location is shared)
5. **Add guardian feedback** (show which users responded)
6. **Handle offline scenarios** (queue requests when offline)
7. **Add location staleness indicator** (show how old the location is)

---

## Contact & Support

If you're still experiencing issues after following this guide:
1. Collect logs from both devices
2. Check Supabase Edge Function logs
3. Verify database state
4. Share the specific error messages you're seeing
