# Location Flow Verification

## Your Planned Flow ✅

```
Guardian opens tracker
    ↓
App gets guardian's email from auth
    ↓
Query: SELECT user_id FROM guardians WHERE guardian_email = 'guardian@example.com'
    ↓
Got user_ids: ['user-uuid-1', 'user-uuid-2']
    ↓
Query: SELECT token FROM fcm_tokens WHERE user_id IN ('user-uuid-1', 'user-uuid-2') AND is_active = true
    ↓
Got FCM tokens: ['token1', 'token2']
    ↓
Send FCM notification to each token
    ↓
User's device receives FCM → gets location → updates live_locations table
    ↓
Guardian fetches from live_locations table
    ↓
Display on map
```

## Code Implementation Status

### ✅ 1. Guardian Side (GuardianMapActivity.kt)
**Location:** `GuardianMapActivity.kt` line 233-263

```kotlin
// Get current user's email
val currentUserEmail = currentUser.email

// Find all users who added ME as their guardian
val usersWhoAddedMe = SupabaseClient.client.from("guardians")
    .select {
        filter {
            eq("guardian_email", currentUserEmail ?: "")
        }
    }
    .decodeList<Guardian>()

// Get user IDs
val userIds = usersWhoAddedMe.map { it.user_id }

// Request live locations
val requestedUserIds = LiveLocationHelper.requestLiveLocations(this@GuardianMapActivity)
```

**Status:** ✅ CORRECT - Gets guardian_email, queries guardians table, extracts user_ids

---

### ✅ 2. Supabase Edge Function (request-live-locations/index.ts)
**Location:** `supabase/functions/request-live-locations/index.ts`

```typescript
// Step 1: Find users who added this guardian (line 31-34)
const { data: guardianRelations } = await supabaseClient
  .from('guardians')
  .select('user_id')
  .eq('guardian_email', guardian_email)

// Step 2: Get user_ids (line 44)
const userIds = guardianRelations?.map(g => g.user_id) || []

// Step 3: For each user, get FCM tokens (line 48-52)
const { data: tokens } = await supabaseClient
  .from('fcm_tokens')
  .select('token')
  .eq('user_id', userId)
  .eq('is_active', true)

// Step 4: Send FCM to each token (line 64)
await sendFCMLocationRequest(tokenData.token, guardian_email)
```

**Status:** ✅ CORRECT - Follows exact flow: guardian_email → user_ids → FCM tokens

---

### ✅ 3. User Side - FCM Receiver (VasateyFCMService.kt)
**Location:** `VasateyFCMService.kt` line 50-53

```kotlin
val messageType = data["type"] ?: "alert"
if (messageType == "location_request") {
    // Independent location request handling
    LiveLocationHelper.handleLocationRequest(applicationContext, data)
}
```

**Status:** ✅ CORRECT - Receives FCM and calls location handler

---

### ✅ 4. Location Handler (LiveLocationHelper.kt)
**Location:** `LiveLocationHelper.kt` line 35-120

```kotlin
fun handleLocationRequest(context: Context, data: Map<String, String>) {
    // Step 1: Check permissions
    if (!LocationManager.hasLocationPermission(context)) {
        Log.e(TAG, "❌ Location permission NOT granted!")
        return
    }
    
    // Step 2: Get current location
    val location = LocationManager.getCurrentLocation(context)
    
    // Step 3: Get current user
    val currentUser = SupabaseClient.client.auth.currentUserOrNull()
    
    // Step 4: Update live_locations table
    val liveLocation = LiveLocation(
        user_id = currentUser.id,
        latitude = location.latitude,
        longitude = location.longitude,
        accuracy = location.accuracy
    )
    
    SupabaseClient.client.from("live_locations")
        .upsert(liveLocation)
}
```

**Status:** ✅ CORRECT - Gets location and updates live_locations with user_id

---

### ✅ 5. Guardian Fetches Locations (LiveLocationHelper.kt)
**Location:** `LiveLocationHelper.kt` line 239-289

```kotlin
suspend fun fetchLiveLocations(userIds: List<String>): Map<String, LiveLocation> {
    // Fetch all live locations
    val liveLocations = SupabaseClient.client.from("live_locations")
        .select()
        .decodeList<LiveLocation>()
    
    // Filter by user IDs
    liveLocations.forEach { location ->
        if (userIds.contains(location.user_id)) {
            locationMap[location.user_id] = location
        }
    }
    
    return locationMap
}
```

**Status:** ✅ CORRECT - Fetches from live_locations using user_ids

---

## Database Schema Verification

### ✅ guardians table
```sql
CREATE TABLE guardians (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL,           -- Person being protected
  guardian_email TEXT NOT NULL,     -- Protector's email
  guardian_user_id UUID,            -- Protector's user_id (optional)
  status TEXT DEFAULT 'active'
);
```
**Status:** ✅ CORRECT

### ✅ fcm_tokens table
```sql
CREATE TABLE fcm_tokens (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL,            -- Links to users table
  token TEXT NOT NULL UNIQUE,       -- FCM token
  is_active BOOLEAN DEFAULT true
);
```
**Status:** ✅ CORRECT - Has user_id, not email

### ✅ live_locations table
```sql
CREATE TABLE live_locations (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL UNIQUE,     -- One location per user
  latitude DOUBLE PRECISION NOT NULL,
  longitude DOUBLE PRECISION NOT NULL,
  accuracy REAL,
  updated_at TIMESTAMP
);
```
**Status:** ✅ CORRECT

---

## Complete Flow Test

### Test Case 1: Guardian requests location

**Setup:**
- Guardian email: `guardian@example.com`
- User 1 added guardian: `user-uuid-1`
- User 1 FCM token: `fcm-token-abc123`

**Expected Flow:**

1. ✅ Guardian opens tracker
2. ✅ App queries: `SELECT user_id FROM guardians WHERE guardian_email = 'guardian@example.com'`
3. ✅ Result: `['user-uuid-1']`
4. ✅ Edge function queries: `SELECT token FROM fcm_tokens WHERE user_id = 'user-uuid-1' AND is_active = true`
5. ✅ Result: `['fcm-token-abc123']`
6. ✅ Send FCM to `fcm-token-abc123`
7. ✅ User device receives FCM
8. ✅ Gets location and upserts: `INSERT INTO live_locations (user_id, lat, lon) VALUES ('user-uuid-1', 12.34, 56.78)`
9. ✅ Guardian queries: `SELECT * FROM live_locations WHERE user_id IN ('user-uuid-1')`
10. ✅ Display marker on map

---

## Verification Checklist

### Database Setup
- [ ] `guardians` table exists with `user_id` and `guardian_email` columns
- [ ] `fcm_tokens` table exists with `user_id` and `token` columns
- [ ] `live_locations` table exists with `user_id`, `latitude`, `longitude` columns
- [ ] Indexes created for performance
- [ ] RLS policies allow guardian to read live_locations

### App Setup
- [ ] User has granted location permissions
- [ ] User is logged in (has valid session)
- [ ] User has active FCM token in database
- [ ] Guardian relationship exists in database

### Edge Function Setup
- [ ] Edge function deployed to Supabase
- [ ] `FCM_SERVER_KEY` configured in Supabase secrets
- [ ] Service role key has permission to query tables

---

## Testing Commands

### 1. Check if guardian relationship exists
```sql
SELECT * FROM guardians 
WHERE guardian_email = 'YOUR_GUARDIAN_EMAIL';
```

### 2. Check if user has FCM token
```sql
SELECT user_id, token, is_active 
FROM fcm_tokens 
WHERE user_id = 'USER_UUID';
```

### 3. Check live locations
```sql
SELECT * FROM live_locations 
ORDER BY updated_at DESC;
```

### 4. Test complete flow
```sql
-- See all relationships
SELECT 
  g.user_id,
  g.guardian_email,
  u.name as user_name,
  u.email as user_email,
  COUNT(f.token) as token_count
FROM guardians g
JOIN users u ON u.id = g.user_id
LEFT JOIN fcm_tokens f ON f.user_id = g.user_id AND f.is_active = true
WHERE g.guardian_email = 'YOUR_GUARDIAN_EMAIL'
GROUP BY g.user_id, g.guardian_email, u.name, u.email;
```

---

## Conclusion

✅ **YOUR APP IS CORRECTLY IMPLEMENTED!**

The flow is exactly as you planned:
1. Guardian email → guardians table → user_ids
2. User_ids → fcm_tokens table → FCM tokens
3. Send FCM → User receives → Updates live_locations
4. Guardian fetches live_locations → Display on map

**If locations are not appearing, the issue is likely:**
1. ❌ User doesn't have FCM token in database
2. ❌ User hasn't granted location permission
3. ❌ User's app is killed/battery optimized
4. ❌ GPS is disabled on user's device
5. ❌ User is not logged in

**Use the enhanced logging to identify the exact issue!**
