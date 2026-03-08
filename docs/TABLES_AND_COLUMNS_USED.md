# Tables and Columns Used for Location Tracking

## 1. GUARDIANS TABLE

**Purpose:** Stores who is protecting whom

**Columns Used:**
```sql
guardians
├── id (UUID)                    -- Primary key
├── user_id (UUID)              -- ✅ USED: Person being protected
├── guardian_email (TEXT)       -- ✅ USED: Protector's email address
├── guardian_user_id (UUID)     -- NOT USED in location tracking
└── status (TEXT)               -- NOT USED in location tracking
```

**How It's Used:**
```kotlin
// GuardianMapActivity.kt line 233-239
SupabaseClient.client.from("guardians")
    .select {
        filter {
            eq("guardian_email", currentUserEmail)  // ✅ USES guardian_email
        }
    }
    .decodeList<Guardian>()

// Then extracts: user_id from results
val userIds = usersWhoAddedMe.map { it.user_id }  // ✅ USES user_id
```

**Query Example:**
```sql
SELECT user_id 
FROM guardians 
WHERE guardian_email = 'guardian@example.com';
```

---

## 2. FCM_TOKENS TABLE

**Purpose:** Stores Firebase Cloud Messaging tokens for push notifications

**Columns Used:**
```sql
fcm_tokens
├── id (UUID)                    -- Primary key
├── user_id (UUID)              -- ✅ USED: Links to users table
├── token (TEXT)                -- ✅ USED: FCM token for notifications
├── device_id (TEXT)            -- NOT USED in location tracking
├── device_name (TEXT)          -- Used only for debugging logs
├── platform (TEXT)             -- NOT USED in location tracking
├── is_active (BOOLEAN)         -- ✅ USED: Filter active tokens only
├── last_used_at (TIMESTAMP)    -- NOT USED in location tracking
├── created_at (TIMESTAMP)      -- NOT USED in location tracking
└── updated_at (TIMESTAMP)      -- NOT USED in location tracking
```

**How It's Used:**
```typescript
// Supabase Edge Function: request-live-locations/index.ts line 48-52
await supabaseClient
  .from('fcm_tokens')
  .select('token')                    // ✅ SELECTS token
  .eq('user_id', userId)              // ✅ FILTERS BY user_id
  .eq('is_active', true)              // ✅ FILTERS BY is_active
```

**Query Example:**
```sql
SELECT token 
FROM fcm_tokens 
WHERE user_id = 'user-uuid-123' 
  AND is_active = true;
```

---

## 3. LIVE_LOCATIONS TABLE

**Purpose:** Stores current real-time locations of users

**Columns Used:**
```sql
live_locations
├── id (UUID)                    -- Primary key
├── user_id (UUID)              -- ✅ USED: Links to users table (UNIQUE)
├── latitude (DOUBLE PRECISION) -- ✅ USED: Latitude coordinate
├── longitude (DOUBLE PRECISION)-- ✅ USED: Longitude coordinate
├── accuracy (REAL)             -- ✅ USED: Location accuracy in meters
├── created_at (TIMESTAMP)      -- NOT USED in location tracking
└── updated_at (TIMESTAMP)      -- NOT USED in location tracking
```

**How It's Used:**

**Writing (User Side):**
```kotlin
// LiveLocationHelper.kt line 93-103
val liveLocation = LiveLocation(
    user_id = currentUser.id,        // ✅ USES user_id
    latitude = location.latitude,    // ✅ USES latitude
    longitude = location.longitude,  // ✅ USES longitude
    accuracy = location.accuracy     // ✅ USES accuracy
)

SupabaseClient.client.from("live_locations")
    .upsert(liveLocation)
```

**Reading (Guardian Side):**
```kotlin
// LiveLocationHelper.kt line 250-263
val liveLocations = SupabaseClient.client.from("live_locations")
    .select()                        // ✅ SELECTS all columns
    .decodeList<LiveLocation>()

// Filter by user_ids
liveLocations.forEach { location ->
    if (userIds.contains(location.user_id)) {  // ✅ USES user_id
        locationMap[location.user_id] = location
    }
}
```

**Query Examples:**
```sql
-- Insert/Update location
INSERT INTO live_locations (user_id, latitude, longitude, accuracy)
VALUES ('user-uuid-123', 12.9716, 77.5946, 15.5)
ON CONFLICT (user_id) DO UPDATE SET
  latitude = EXCLUDED.latitude,
  longitude = EXCLUDED.longitude,
  accuracy = EXCLUDED.accuracy,
  updated_at = NOW();

-- Fetch locations
SELECT user_id, latitude, longitude, accuracy 
FROM live_locations 
WHERE user_id IN ('user-uuid-1', 'user-uuid-2');
```

---

## 4. USERS TABLE

**Purpose:** Stores user profile information

**Columns Used:**
```sql
users
├── id (UUID)                    -- ✅ USED: User identifier
├── name (TEXT)                 -- ✅ USED: Display name on map markers
├── email (TEXT)                -- ✅ USED: For guardian matching
├── phone (TEXT)                -- NOT USED in location tracking
├── created_at (TIMESTAMP)      -- NOT USED in location tracking
└── updated_at (TIMESTAMP)      -- NOT USED in location tracking
```

**How It's Used:**
```kotlin
// GuardianMapActivity.kt line 285-295
val users = SupabaseClient.client.from("users")
    .select {
        filter {
            eq("id", userId)         // ✅ FILTERS BY id
        }
    }
    .decodeList<User>()

val userName = user.name            // ✅ USES name for marker title
```

**Query Example:**
```sql
SELECT id, name, email 
FROM users 
WHERE id = 'user-uuid-123';
```

---

## COMPLETE DATA FLOW WITH COLUMNS

### Step 1: Guardian Opens Map
```
Guardian's email: guardian@example.com
```

### Step 2: Find Users Who Added This Guardian
```sql
SELECT user_id                    -- ✅ Column: user_id
FROM guardians 
WHERE guardian_email = 'guardian@example.com'  -- ✅ Column: guardian_email
  AND status = 'active';          -- ✅ Column: status

Result: ['user-uuid-1', 'user-uuid-2']
```

### Step 3: Get FCM Tokens for Those Users
```sql
SELECT token                      -- ✅ Column: token
FROM fcm_tokens 
WHERE user_id IN ('user-uuid-1', 'user-uuid-2')  -- ✅ Column: user_id
  AND is_active = true;           -- ✅ Column: is_active

Result: ['fcm-token-abc', 'fcm-token-xyz']
```

### Step 4: Send FCM Notifications
```
Send to: fcm-token-abc
Send to: fcm-token-xyz
Data: {type: 'location_request', guardianEmail: 'guardian@example.com'}
```

### Step 5: User Receives FCM and Updates Location
```sql
INSERT INTO live_locations (
  user_id,                        -- ✅ Column: user_id
  latitude,                       -- ✅ Column: latitude
  longitude,                      -- ✅ Column: longitude
  accuracy                        -- ✅ Column: accuracy
) VALUES (
  'user-uuid-1',
  12.9716,
  77.5946,
  15.5
)
ON CONFLICT (user_id) DO UPDATE SET
  latitude = EXCLUDED.latitude,
  longitude = EXCLUDED.longitude,
  accuracy = EXCLUDED.accuracy,
  updated_at = NOW();
```

### Step 6: Guardian Fetches Locations
```sql
SELECT 
  user_id,                        -- ✅ Column: user_id
  latitude,                       -- ✅ Column: latitude
  longitude,                      -- ✅ Column: longitude
  accuracy                        -- ✅ Column: accuracy
FROM live_locations 
WHERE user_id IN ('user-uuid-1', 'user-uuid-2');

Result:
[
  {user_id: 'user-uuid-1', latitude: 12.9716, longitude: 77.5946, accuracy: 15.5},
  {user_id: 'user-uuid-2', latitude: 13.0827, longitude: 80.2707, accuracy: 20.0}
]
```

### Step 7: Get User Names for Display
```sql
SELECT 
  id,                             -- ✅ Column: id
  name                            -- ✅ Column: name
FROM users 
WHERE id IN ('user-uuid-1', 'user-uuid-2');

Result:
[
  {id: 'user-uuid-1', name: 'John Doe'},
  {id: 'user-uuid-2', name: 'Jane Smith'}
]
```

### Step 8: Display on Map
```
Green Marker: "John Doe" at (12.9716, 77.5946)
Green Marker: "Jane Smith" at (13.0827, 80.2707)
```

---

## SUMMARY OF COLUMNS USED

### ✅ GUARDIANS TABLE
- `user_id` - Person being protected
- `guardian_email` - Protector's email

### ✅ FCM_TOKENS TABLE
- `user_id` - Links to user
- `token` - FCM token
- `is_active` - Filter active tokens

### ✅ LIVE_LOCATIONS TABLE
- `user_id` - User identifier (UNIQUE)
- `latitude` - Location latitude
- `longitude` - Location longitude
- `accuracy` - Location accuracy

### ✅ USERS TABLE
- `id` - User identifier
- `name` - Display name
- `email` - User email

---

## COLUMNS NOT USED (But Present in Schema)

### GUARDIANS TABLE
- `guardian_user_id` - Not used in location tracking
- `status` - Could be used to filter active guardians

### FCM_TOKENS TABLE
- `device_id` - Not used
- `device_name` - Only used in debug logs
- `platform` - Not used
- `last_used_at` - Not used
- `created_at` - Not used
- `updated_at` - Not used

### LIVE_LOCATIONS TABLE
- `created_at` - Not used
- `updated_at` - Not used (but auto-updated by trigger)

### USERS TABLE
- `phone` - Not used in location tracking
- `created_at` - Not used
- `updated_at` - Not used

---

## INDEXES USED FOR PERFORMANCE

```sql
-- For finding users who added guardian
CREATE INDEX idx_guardians_email ON guardians(guardian_email);

-- For finding FCM tokens by user
CREATE INDEX idx_fcm_tokens_user_id ON fcm_tokens(user_id);
CREATE INDEX idx_fcm_tokens_active ON fcm_tokens(user_id, is_active);

-- For fetching live locations
CREATE INDEX idx_live_locations_user_id ON live_locations(user_id);

-- For getting user details
CREATE INDEX idx_users_email ON users(email);
```

---

## DATA MODELS IN KOTLIN

### Guardian Model
```kotlin
@Serializable
data class Guardian(
    val id: String? = null,
    val user_id: String,           // ✅ USED
    val guardian_email: String,    // ✅ USED
    val guardian_user_id: String? = null,
    val status: String = "active"
)
```

### FCMToken Model
```kotlin
@Serializable
data class FCMToken(
    val id: String? = null,
    val user_id: String,           // ✅ USED
    val token: String,             // ✅ USED
    val device_id: String? = null,
    val device_name: String? = null,
    val platform: String = "android",
    val is_active: Boolean = true  // ✅ USED
)
```

### LiveLocation Model
```kotlin
@Serializable
data class LiveLocation(
    val id: String? = null,
    val user_id: String,           // ✅ USED
    val latitude: Double,          // ✅ USED
    val longitude: Double,         // ✅ USED
    val accuracy: Float            // ✅ USED
)
```

### User Model
```kotlin
@Serializable
data class User(
    val id: String,                // ✅ USED
    val name: String,              // ✅ USED
    val email: String,             // ✅ USED
    val phone: String? = null
)
```
