# 📍 Complete Live Location Tracking Guide

## 🎯 Overview

This system allows guardians to see **live locations** of users when they open the Track page. It uses Supabase Edge Functions to handle everything server-side.

---

## 🚀 How It Works

```
Guardian Opens Track
        ↓
App calls Supabase Edge Function
        ↓
Function sends FCM to all users
        ↓
Users' apps get location & update live_locations table
        ↓
App fetches from live_locations
        ↓
Shows 🟢 Green (live) or 🟠 Orange (old) markers
```

---

## 📋 Setup Steps

### Step 1: Create Database Table

Run this SQL in Supabase SQL Editor:

```sql
-- Create live_locations table
CREATE TABLE IF NOT EXISTS live_locations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    accuracy FLOAT,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id)
);

-- Create indexes
CREATE INDEX idx_live_locations_user_id ON live_locations(user_id);
CREATE INDEX idx_live_locations_updated_at ON live_locations(updated_at);

-- Enable RLS
ALTER TABLE live_locations ENABLE ROW LEVEL SECURITY;

-- Users can update their own location
CREATE POLICY "Users can manage their own location"
ON live_locations FOR ALL
USING (auth.uid() = user_id)
WITH CHECK (auth.uid() = user_id);

-- Guardians can view locations
CREATE POLICY "Guardians can view their users' locations"
ON live_locations FOR SELECT
USING (
    EXISTS (
        SELECT 1 FROM guardians
        WHERE guardians.user_id = live_locations.user_id
        AND guardians.guardian_email = (
            SELECT email FROM auth.users WHERE id = auth.uid()
        )
    )
);
```

### Step 2: Deploy Supabase Edge Function

```bash
# Install Supabase CLI
npm install -g supabase

# Login
supabase login

# Link to your project
supabase link --project-ref YOUR_PROJECT_REF

# Deploy function
cd c:\siddu\vasateysec
supabase functions deploy request-live-locations

# Set FCM Server Key
supabase secrets set FCM_SERVER_KEY=YOUR_FCM_SERVER_KEY
```

**Get FCM Server Key:**
- Firebase Console → Project Settings → Cloud Messaging → Server key

### Step 3: Update App Configuration

Edit `LiveLocationHelper.kt` (lines 134-148):

```kotlin
// Replace with your values:
val supabaseUrl = "https://YOUR_PROJECT_REF.supabase.co"
// ...
.addHeader("apikey", "YOUR_SUPABASE_ANON_KEY")
```

**Get your values:**
- Supabase Dashboard → Settings → API
- URL: `https://YOUR_PROJECT_REF.supabase.co`
- Anon Key: Copy the `anon` `public` key

### Step 4: Integrate into GuardianMapActivity

Add this code to `GuardianMapActivity.kt`:

```kotlin
// 1. Add import at top
import com.sriox.vasateysec.utils.LiveLocationHelper

// 2. In loadTrackedUsersLocations(), after finding usersWhoAddedMe:
android.util.Log.d("GuardianMap", "Found ${usersWhoAddedMe.size} users who added me as guardian")

// Request live locations via Supabase function
android.util.Log.d("GuardianMap", "📍 Requesting live locations...")
val requestedUserIds = LiveLocationHelper.requestLiveLocations(this@GuardianMapActivity)

// Wait 3 seconds for users to respond
Toast.makeText(this@GuardianMapActivity, "Requesting live locations...", Toast.LENGTH_SHORT).show()
kotlinx.coroutines.delay(3000)

// Fetch live locations from database
val liveLocations = LiveLocationHelper.fetchLiveLocations(requestedUserIds)
android.util.Log.d("GuardianMap", "📍 Received ${liveLocations.size} live locations")

// 3. Update the forEach loop to check live locations first:
usersWhoAddedMe.forEach { guardianRelation ->
    val userId = guardianRelation.user_id
    
    try {
        val users = SupabaseClient.client.from("users")
            .select { filter { eq("id", userId) } }
            .decodeList<com.sriox.vasateysec.models.User>()

        if (users.isNotEmpty()) {
            val user = users[0]
            val userName = user.name
            
            // Check if we have live location
            val liveLocation = liveLocations[userId]
            
            if (liveLocation != null) {
                // Use LIVE location (green marker)
                addUserMarker(userName, liveLocation.latitude, liveLocation.longitude, isLive = true)
                android.util.Log.d("GuardianMap", "🟢 Added LIVE marker for $userName")
            } else {
                // Fallback to alert history (orange marker)
                val alerts = SupabaseClient.client.from("alert_history")
                    .select { filter { eq("user_id", userId) } }
                    .decodeList<com.sriox.vasateysec.models.AlertHistory>()

                if (alerts.isNotEmpty()) {
                    val recentAlert = alerts
                        .sortedByDescending { it.created_at }
                        .firstOrNull { it.latitude != null && it.longitude != null }

                    recentAlert?.let { alert ->
                        if (alert.latitude != null && alert.longitude != null) {
                            addUserMarker(userName, alert.latitude, alert.longitude, isLive = false)
                            android.util.Log.d("GuardianMap", "🟠 Added old marker for $userName")
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("GuardianMap", "Error loading location for user $userId", e)
    }
}

// 4. Update addUserMarker function:
private fun addUserMarker(name: String, latitude: Double, longitude: Double, isLive: Boolean = false) {
    val position = LatLng(latitude, longitude)
    
    // Green for live, orange for old
    val markerColor = if (isLive) {
        BitmapDescriptorFactory.HUE_GREEN
    } else {
        BitmapDescriptorFactory.HUE_ORANGE
    }
    
    val snippet = if (isLive) {
        "Live location (just now)"
    } else {
        "Last known location"
    }
    
    val marker = googleMap.addMarker(
        MarkerOptions()
            .position(position)
            .title(name)
            .snippet(snippet)
            .icon(BitmapDescriptorFactory.defaultMarker(markerColor))
    )
    
    marker?.let { markers.add(it) }
    
    // Add circle
    googleMap.addCircle(
        CircleOptions()
            .center(position)
            .radius(500.0)
            .strokeColor(Color.parseColor(if (isLive) "#00FF00" else "#FF6B35"))
            .fillColor(Color.parseColor(if (isLive) "#2200FF00" else "#22FF6B35"))
            .strokeWidth(2f)
    )
}
```

---

## ✅ Code Changes Summary

### Files Modified:

1. **VasateyFCMService.kt** - Added 1 check for location requests
   - ✅ Does NOT affect existing alert handling
   - ✅ Routes location requests to LiveLocationHelper

2. **DataModels.kt** - Added LiveLocation data class
   - ✅ New model, doesn't affect existing models

### Files Created:

1. **LiveLocationHelper.kt** - Independent helper
   - ✅ Handles location requests
   - ✅ Calls Supabase function
   - ✅ Updates live_locations table

2. **supabase/functions/request-live-locations/index.ts** - Edge function
   - ✅ Runs on Supabase servers
   - ✅ Sends FCM notifications

### Files NOT Changed (Your existing features work):

- ✅ GuardianMapActivity.kt (until you integrate)
- ✅ AlertManager.kt
- ✅ VoskWakeWordService.kt
- ✅ HomeActivity.kt
- ✅ All other activities

---

## 🧪 Testing

### Test Flow:
1. User A adds User B as guardian
2. User B opens Track page
3. Check logs on User A's phone:
   ```
   LiveLocationHelper: 📍 Location request received
   LiveLocationHelper: ✅ Location obtained: 12.345, 67.890
   LiveLocationHelper: ✅ Live location updated successfully!
   ```
4. User B sees green marker for User A

### Test Supabase Function:
```bash
# In Supabase Dashboard → Edge Functions
POST /request-live-locations
{
  "guardian_email": "test@example.com"
}
```

---

## 🔧 Troubleshooting

### Function not deploying?
```bash
supabase projects list  # Check if logged in
supabase functions logs request-live-locations  # Check logs
```

### Live locations not showing?
- Check `live_locations` table exists in Supabase
- Check FCM_SERVER_KEY is set
- Check user has location permission
- Check app is not in battery optimization

### App crashes?
- The code is independent, won't affect existing features
- Check logs for specific errors

---

## 🎨 Visual Result

- 🟢 **Green Marker** = Live location (just now)
- 🟠 **Orange Marker** = Last known location (from alerts)

---

## 🔒 Security

✅ **Server-Side**: FCM keys stored in Supabase (not in app)
✅ **Privacy**: Location only shared when guardian checks
✅ **Database**: RLS policies protect data
✅ **Scalable**: Serverless, auto-scales

---

## 📊 Database Tables

```
guardians → Supabase Function → fcm_tokens → User's App → live_locations → Guardian's Map
```

---

## 🎉 Benefits

- ✅ All-in-one (everything in Supabase)
- ✅ Secure (no keys in app)
- ✅ Fast (direct DB access)
- ✅ Simple (one function call)
- ✅ Independent (doesn't break existing features)

---

## 📝 Deployment Checklist

- [ ] SQL table created in Supabase
- [ ] Edge function deployed
- [ ] FCM_SERVER_KEY secret set
- [ ] App config updated (Supabase URL & key)
- [ ] GuardianMapActivity integrated
- [ ] App rebuilt and tested

---

**That's it! Follow these steps and you'll have live location tracking!** 🚀
