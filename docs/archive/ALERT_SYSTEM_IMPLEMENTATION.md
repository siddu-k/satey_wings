# Alert System Implementation - VasateySec

## Overview
The VasateySec app implements a comprehensive emergency alert system that allows users to send distress signals to their guardians with location data and photos. The system works both when the app is open and closed, using voice activation or manual triggers.

---

## System Architecture

### Core Components

1. **Alert Manager** (`AlertManager.kt`)
2. **FCM Service** (`VasateyFCMService.kt`)
3. **FCM Token Manager** (`FCMTokenManager.kt`)
4. **Wake Word Service** (`VoskWakeWordService.kt`)
5. **SOS Helper** (`SOSHelper.kt`)
6. **Alert Viewer Activities** (`EmergencyAlertViewerActivity.kt`, `AlertHistoryActivity.kt`)

---

## Database Schema

### Tables

#### 1. **alert_history**
Stores all emergency alerts sent by users.

```sql
CREATE TABLE alert_history (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL,
  user_name TEXT NOT NULL,
  user_email TEXT NOT NULL,
  user_phone TEXT NOT NULL,
  latitude DOUBLE PRECISION,
  longitude DOUBLE PRECISION,
  location_accuracy REAL,
  alert_type TEXT DEFAULT 'voice_help',
  status TEXT DEFAULT 'sent',
  front_photo_url TEXT,
  back_photo_url TEXT,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
)
```

**Fields:**
- `id`: Unique alert identifier
- `user_id`: User who triggered the alert
- `user_name`, `user_email`, `user_phone`: User contact info
- `latitude`, `longitude`, `location_accuracy`: Location data
- `alert_type`: Type of alert (voice_help, manual, emergency)
- `status`: Alert status (sent, acknowledged, resolved)
- `front_photo_url`, `back_photo_url`: URLs to emergency photos in Supabase Storage
- `created_at`: Timestamp of alert creation

#### 2. **alert_recipients**
Tracks which guardians received each alert.

```sql
CREATE TABLE alert_recipients (
  id UUID PRIMARY KEY,
  alert_id UUID NOT NULL,
  guardian_email TEXT NOT NULL,
  guardian_user_id UUID,
  fcm_token TEXT,
  notification_sent BOOLEAN DEFAULT false,
  notification_delivered BOOLEAN DEFAULT false,
  viewed_at TIMESTAMP WITH TIME ZONE,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
)
```

**Fields:**
- `alert_id`: Reference to alert_history
- `guardian_email`: Guardian's email
- `guardian_user_id`: Guardian's user ID (if they have the app)
- `fcm_token`: FCM token used for notification
- `notification_sent`, `notification_delivered`: Delivery tracking
- `viewed_at`: When guardian viewed the alert

#### 3. **fcm_tokens**
Stores Firebase Cloud Messaging tokens for push notifications.

```sql
CREATE TABLE fcm_tokens (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL,
  token TEXT NOT NULL UNIQUE,
  device_id TEXT,
  device_name TEXT,
  platform TEXT DEFAULT 'android',
  is_active BOOLEAN DEFAULT true,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
)
```

#### 4. **guardians**
Stores guardian relationships.

```sql
CREATE TABLE guardians (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL,
  guardian_email TEXT NOT NULL,
  guardian_user_id UUID,
  status TEXT DEFAULT 'active',
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
)
```

---

## Alert Flow

### 1. Alert Triggering

#### Voice Activation (VoskWakeWordService)
```kotlin
// Wake word detection triggers emergency alert
if (resultText.contains(wakeWord, ignoreCase = true)) {
    triggerEmergencyAlert()
}
```

**Process:**
1. Service listens for wake word (default: "help me")
2. On detection, shows immediate feedback notification
3. Captures location with retry logic (3 attempts)
4. Captures photos from front and back cameras
5. Calls `AlertManager.sendEmergencyAlert()`

#### Manual Trigger (SOSHelper)
```kotlin
// User presses SOS button
SOSHelper.showSOSConfirmation(activity)
```

**Process:**
1. Shows confirmation dialog
2. Gets current location
3. Calls `AlertManager.sendEmergencyAlert()`

### 2. Alert Creation (AlertManager)

#### Main Function: `sendEmergencyAlert()`

**Parameters:**
- `context`: Application context
- `latitude`: User's latitude (nullable)
- `longitude`: User's longitude (nullable)
- `locationAccuracy`: GPS accuracy in meters
- `frontPhotoFile`: Front camera photo file
- `backPhotoFile`: Back camera photo file

**Process:**

1. **Get User Info**
   - Try Supabase auth session first
   - Fallback to SessionManager if app is closed
   
2. **Upload Photos to Supabase Storage**
   ```kotlin
   // Parallel upload with 15-second timeout
   withTimeout(15000L) {
       val frontJob = async { uploadPhotoToStorage(userId, frontPhotoFile, "front") }
       val backJob = async { uploadPhotoToStorage(userId, backPhotoFile, "back") }
       frontPhotoUrl = frontJob.await()
       backPhotoUrl = backJob.await()
   }
   ```
   - Bucket: `emergency-photos`
   - Filename format: `emergency_{userId}_{cameraType}_{timestamp}.jpg`
   - Returns public URLs

3. **Create Alert Record**
   ```kotlin
   val alertHistory = AlertHistory(
       user_id = userId,
       user_name = userName,
       user_email = userEmail,
       user_phone = userPhone,
       latitude = latitude,
       longitude = longitude,
       location_accuracy = locationAccuracy,
       alert_type = "voice_help",
       status = "sent",
       front_photo_url = frontPhotoUrl,
       back_photo_url = backPhotoUrl
   )
   ```

4. **Get Guardians**
   ```kotlin
   val guardians = SupabaseClient.client.from("guardians")
       .select {
           filter {
               eq("user_id", userId)
               eq("status", "active")
           }
       }
       .decodeList<Guardian>()
   ```

5. **Get Guardian FCM Tokens**
   ```kotlin
   val guardianTokens = FCMTokenManager.getGuardianTokens(guardianEmails)
   ```

6. **Send Notifications**
   - For each guardian token:
     - Call `sendNotificationToSupabase()`
     - Create `alert_recipients` record
   
7. **Cleanup Old Alerts**
   - Keep only 10 most recent alerts per user
   - Delete older alerts and their recipients

### 3. Notification Sending

#### Function: `sendNotificationToSupabase()`

**Endpoint:** `https://vasatey-notify-msg.vercel.app/api/sendNotification`

**Payload:**
```json
{
  "token": "fcm_token_here",
  "title": "🚨 {userName} needs help!",
  "body": "{userName} has triggered an emergency alert. Tap to view their location.",
  "email": "user@example.com",
  "isSelfAlert": false,
  "fullName": "User Name",
  "phoneNumber": "+1234567890",
  "lastKnownLatitude": 37.7749,
  "lastKnownLongitude": -122.4194,
  "frontPhotoUrl": "https://...",
  "backPhotoUrl": "https://..."
}
```

**Error Handling:**
- Detects invalid/unregistered tokens
- Automatically deactivates invalid tokens in database

### 4. Notification Reception (VasateyFCMService)

#### Function: `onMessageReceived()`

**Process:**
1. Receives FCM data payload
2. Checks message type (alert vs location_request)
3. For alerts:
   - Extracts user info, location, photos
   - Skips if `isSelfAlert` is true
   - Creates rich notification with BigTextStyle
   - Opens `EmergencyAlertViewerActivity` on tap

**Notification Content:**
```kotlin
val detailedText = buildString {
    append("🚨 EMERGENCY ALERT 🚨\n\n")
    append("👤 Name: $fullName\n")
    append("📧 Email: $email\n")
    append("📞 Phone: $phoneNumber\n")
    append("$locationText\n\n")
    append("⚠️ This person needs immediate help!\n")
    append("Tap to view details and location on map.")
}
```

### 5. Alert Viewing

#### EmergencyAlertViewerActivity

**Features:**
- Standalone activity (no login required)
- Displays user info (name, email, phone)
- Shows location on Google Maps (satellite view)
- Displays emergency photos (front and back)
- Call button (opens dialer)
- Navigate button (opens Google Maps navigation)

**Intent Data:**
- `fullName`: User's name
- `email`: User's email
- `phoneNumber`: User's phone
- `latitude`: Location latitude (as string)
- `longitude`: Location longitude (as string)
- `timestamp`: Alert timestamp
- `frontPhotoUrl`: Front camera photo URL
- `backPhotoUrl`: Back camera photo URL

#### AlertHistoryActivity

**Features:**
- Shows all sent and received alerts
- Filter buttons: All, Sent, Received
- Pagination (10 alerts per page)
- Displays alert type with arrows (↑ sent, ↓ received)
- Shows photo thumbnails
- Relative timestamps (e.g., "5 minutes ago")

**Data Loading:**
```kotlin
// Parallel fetch of sent and received alerts
val sentAlerts = async { /* fetch alerts where user_id = currentUser.id */ }
val receivedAlerts = async { /* fetch alerts via alert_recipients */ }
```

---

## FCM Token Management

### Token Lifecycle

1. **Token Generation**
   ```kotlin
   FirebaseMessaging.getInstance().token.await()
   ```

2. **Token Storage**
   - Saved locally in SharedPreferences
   - Saved to Supabase `fcm_tokens` table
   - One active token per user (old tokens deleted)

3. **Token Update**
   ```kotlin
   override fun onNewToken(token: String) {
       FCMTokenManager.updateFCMToken(applicationContext, token)
   }
   ```

4. **Token Deactivation**
   - On logout: `FCMTokenManager.deactivateFCMToken()`
   - On invalid token detection: `FCMTokenManager.deactivateInvalidToken()`

### Getting Guardian Tokens

```kotlin
suspend fun getGuardianTokens(guardianEmails: List<String>): List<Pair<String, String>> {
    for (email in guardianEmails) {
        // 1. Get user ID from email
        val users = supabase.from("users")
            .select { filter { eq("email", email) } }
        
        // 2. Get active FCM tokens for user
        val tokens = supabase.from("fcm_tokens")
            .select { filter { 
                eq("user_id", userId)
                eq("is_active", true)
            }}
        
        // 3. Return first active token
        tokens.add(Pair(email, token))
    }
}
```

---

## Photo Capture System

### CameraManager Integration

**Function:** `CameraManager.captureEmergencyPhotos()`

**Returns:**
```kotlin
data class CapturedPhotos(
    val frontPhoto: File?,
    val backPhoto: File?
)
```

**Upload Process:**
1. Photos saved to app's cache directory
2. Uploaded to Supabase Storage bucket `emergency-photos`
3. Public URLs returned
4. URLs stored in `alert_history` table

**Storage Path:**
```
emergency-photos/
  └── emergency_{userId}_front_{timestamp}.jpg
  └── emergency_{userId}_back_{timestamp}.jpg
```

---

## Location Tracking

### Location Capture

**Sources (in order of preference):**
1. GPS Provider
2. Network Provider
3. Cached location from SharedPreferences

**Retry Logic:**
```kotlin
val maxAttempts = 3
for (attempts in 1..maxAttempts) {
    location = LocationManager.getCurrentLocation(context)
    if (location != null) break
    delay(3000) // Wait 3 seconds between attempts
}
```

**Caching:**
```kotlin
// Save to SharedPreferences for future use
prefs.edit().apply {
    putString("last_known_lat", location.latitude.toString())
    putString("last_known_lon", location.longitude.toString())
    putFloat("last_known_accuracy", location.accuracy)
    putLong("last_known_time", location.time)
    apply()
}
```

---

## Settings Integration

### User Preferences

**SharedPreferences:** `vasatey_settings`

**Settings:**
1. **location_tracking_enabled** (default: true)
   - Controls whether location is captured with alerts
   
2. **photo_capture_enabled** (default: true)
   - Controls whether photos are captured with alerts

3. **wake_word** (default: "help me")
   - Custom wake word for voice activation

**Usage in Alert Flow:**
```kotlin
val locationTrackingEnabled = prefs.getBoolean("location_tracking_enabled", true)
val photoCaptureEnabled = prefs.getBoolean("photo_capture_enabled", true)

if (locationTrackingEnabled) {
    location = LocationManager.getCurrentLocation(context)
}

if (photoCaptureEnabled) {
    photos = CameraManager.captureEmergencyPhotos(context)
}
```

---

## Error Handling

### Common Error Scenarios

1. **No Guardians Found**
   ```kotlin
   if (guardians.isEmpty()) {
       return Result.failure(Exception("No guardians found. Please add guardians first."))
   }
   ```

2. **No FCM Tokens**
   ```kotlin
   if (guardianTokens.isEmpty()) {
       return Result.failure(Exception("No active guardians found with the app installed"))
   }
   ```

3. **Photo Upload Timeout**
   ```kotlin
   try {
       withTimeout(15000L) { /* upload photos */ }
   } catch (e: TimeoutCancellationException) {
       Log.e(TAG, "Photo upload timeout - proceeding without photos")
   }
   ```

4. **Invalid FCM Token**
   ```kotlin
   if (responseBody?.contains("registration-token-not-registered") == true) {
       FCMTokenManager.deactivateInvalidToken(token)
   }
   ```

5. **Location Unavailable**
   - Falls back to cached location
   - Sends alert without location if no cache available

---

## Performance Optimizations

### 1. Parallel Operations
```kotlin
// Upload photos in parallel
val frontJob = async { uploadPhotoToStorage(...) }
val backJob = async { uploadPhotoToStorage(...) }
frontPhotoUrl = frontJob.await()
backPhotoUrl = backJob.await()
```

### 2. Pagination
- Alert history loads 10 alerts at a time
- "Load More" button for additional alerts

### 3. Token Cleanup
- Only one active token per user
- Old tokens automatically deactivated
- Invalid tokens removed on detection

### 4. Alert Cleanup
- Keep only 10 most recent alerts per user
- Automatic cleanup after each new alert

---

## Security Considerations

### Row Level Security (RLS)

**alert_history:**
- Users can view their own alerts
- Guardians can view alerts sent to them
- Users can create their own alerts

**alert_recipients:**
- Users can view recipients of their own alerts
- Guardians can view their received alerts
- System can insert recipients

**fcm_tokens:**
- Users can manage their own tokens
- All users can read tokens (for alert sending)

### Data Privacy

1. **Photo Storage:**
   - Stored in Supabase Storage with public URLs
   - Only accessible via direct URL
   - Automatically cleaned up with old alerts

2. **Location Data:**
   - Only shared with designated guardians
   - Stored with accuracy information
   - Can be disabled in settings

3. **FCM Tokens:**
   - Encrypted in transit
   - Stored securely in Supabase
   - Automatically invalidated on logout

---

## Testing & Debugging

### Log Tags

- `AlertManager`: Alert creation and sending
- `VasateyFCMService`: FCM message reception
- `FCMTokenManager`: Token management
- `VoskService`: Wake word detection
- `AlertHistory`: Alert history loading
- `EmergencyViewer`: Alert viewing

### Key Log Points

1. **Alert Creation:**
   ```kotlin
   Log.d(TAG, "Sending alert for user: $userName ($userEmail)")
   Log.d(TAG, "Alert created with ID: $alertId")
   ```

2. **Photo Upload:**
   ```kotlin
   Log.d(TAG, "📤 Uploading front photo to Supabase Storage")
   Log.d(TAG, "✅ Photo uploaded successfully! URL: $publicUrl")
   ```

3. **Notification Sending:**
   ```kotlin
   Log.d(TAG, "📤 Sending notification to GUARDIAN: $guardianEmail")
   Log.d(TAG, "✅ Notification sent successfully")
   ```

4. **FCM Reception:**
   ```kotlin
   Log.d(TAG, "Message received from: ${message.from}")
   Log.d(TAG, "Notification data: lat=$latitudeStr, lon=$longitudeStr")
   ```

---

## API Endpoints

### 1. Vercel Notification Endpoint
**URL:** `https://vasatey-notify-msg.vercel.app/api/sendNotification`

**Method:** POST

**Headers:**
- `Content-Type: application/json`

**Body:**
```json
{
  "token": "string",
  "title": "string",
  "body": "string",
  "email": "string",
  "isSelfAlert": boolean,
  "fullName": "string",
  "phoneNumber": "string",
  "lastKnownLatitude": number | null,
  "lastKnownLongitude": number | null,
  "frontPhotoUrl": string | null,
  "backPhotoUrl": string | null
}
```

**Response:**
```json
{
  "success": true,
  "messageId": "string"
}
```

### 2. Supabase Edge Function (Live Location Request)
**URL:** `{SUPABASE_URL}/functions/v1/request-live-locations`

**Method:** POST

**Headers:**
- `Content-Type: application/json`
- `Authorization: Bearer {SUPABASE_ANON_KEY}`

**Body:**
```json
{
  "guardianEmail": "string"
}
```

**Response:**
```json
{
  "success": true,
  "users_requested": number,
  "message": "string"
}
```

---

## Data Models

### AlertHistory
```kotlin
@Serializable
data class AlertHistory(
    val id: String? = null,
    val user_id: String,
    val user_name: String,
    val user_email: String,
    val user_phone: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val location_accuracy: Float? = null,
    val alert_type: String = "voice_help",
    val status: String = "sent",
    val created_at: String? = null,
    val front_photo_url: String? = null,
    val back_photo_url: String? = null
)
```

### AlertRecipient
```kotlin
@Serializable
data class AlertRecipient(
    val id: String? = null,
    val alert_id: String,
    val guardian_email: String,
    val guardian_user_id: String? = null,
    val fcm_token: String? = null,
    val notification_sent: Boolean = false,
    val notification_delivered: Boolean = false,
    val viewed_at: String? = null
)
```

### FCMToken
```kotlin
@Serializable
data class FCMToken(
    val id: String? = null,
    val user_id: String,
    val token: String,
    val device_id: String? = null,
    val device_name: String? = null,
    val platform: String = "android",
    val is_active: Boolean = true
)
```

### Guardian
```kotlin
@Serializable
data class Guardian(
    val id: String? = null,
    val user_id: String,
    val guardian_email: String,
    val guardian_user_id: String? = null,
    val status: String = "active"
)
```

---

## Future Enhancements

### Potential Improvements

1. **Real-time Location Tracking**
   - Continuous location updates during emergency
   - Live location sharing with guardians

2. **Audio Recording**
   - Record audio during emergency
   - Upload to Supabase Storage

3. **Video Recording**
   - Short video clips from cameras
   - Automatic upload with alert

4. **Alert Acknowledgment**
   - Guardians can acknowledge receipt
   - Track response times

5. **Emergency Contacts**
   - Automatic SMS to emergency contacts
   - Integration with local emergency services

6. **Geofencing**
   - Trigger alerts when entering/leaving zones
   - Safe zone notifications

7. **Battery Optimization**
   - Reduce battery drain during wake word listening
   - Optimize photo capture

8. **Multi-language Support**
   - Support for multiple wake words
   - Localized notifications

---

## Troubleshooting Guide

### Issue: Notifications Not Received

**Possible Causes:**
1. FCM token not registered
2. Guardian doesn't have app installed
3. Invalid/expired token
4. Network connectivity issues

**Solutions:**
1. Check FCM token in database
2. Verify guardian has app and is logged in
3. Test with manual notification
4. Check Vercel endpoint logs

### Issue: Location Not Available

**Possible Causes:**
1. Location services disabled
2. No GPS signal
3. Permissions not granted
4. Location timeout

**Solutions:**
1. Enable location in device settings
2. Wait for GPS signal
3. Grant location permissions
4. Use cached location fallback

### Issue: Photos Not Uploading

**Possible Causes:**
1. Camera permissions not granted
2. Storage space full
3. Network timeout
4. Supabase storage bucket not configured

**Solutions:**
1. Grant camera permissions
2. Clear app cache
3. Increase timeout duration
4. Verify Supabase storage setup

### Issue: Wake Word Not Detected

**Possible Causes:**
1. Vosk model not loaded
2. Microphone permissions not granted
3. Background service stopped
4. Wrong wake word configured

**Solutions:**
1. Verify model files in assets
2. Grant microphone permissions
3. Restart wake word service
4. Check wake word in settings

---

## Conclusion

The VasateySec alert system is a comprehensive emergency response solution that integrates:
- Voice-activated alerts
- Real-time location tracking
- Emergency photo capture
- Push notifications via FCM
- Guardian management
- Alert history and viewing

The system is designed to work reliably even when the app is closed, providing users with peace of mind and guardians with critical information during emergencies.
