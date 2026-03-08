# Vasatey Safety App - Complete Setup Guide

## 🎉 Implementation Complete!

All core features have been implemented. Follow these steps to configure and run the app.

---

## 📋 What's Been Implemented

### ✅ Database & Backend
- **Supabase Schema**: 5 tables with RLS policies
- **FCM Token Management**: Auto-deactivation and multi-device support
- **Guardian System**: Email-based guardian relationships
- **Alert History**: Complete tracking of all emergency alerts

### ✅ Authentication
- Login with email/password
- Signup with name, email, phone, password
- Auto-login check
- Secure logout with FCM token cleanup

### ✅ Core Features
- **Home Dashboard**: Feature overview + quick actions
- **Navigation Drawer**: Hamburger menu with all options
- **Add Guardians**: Add/remove guardians by email
- **Voice Activation**: "Help me" triggers emergency alert
- **Location Tracking**: Real-time location sharing
- **Push Notifications**: FCM data-only notifications
- **Alert History**: View sent/received alerts
- **Help Request View**: Map + user info display
- **Profile Editing**: Update name and phone

### ✅ Services & Utilities
- **VasateyFCMService**: Handles incoming notifications
- **FCMTokenManager**: Token lifecycle management
- **AlertManager**: Emergency alert orchestration
- **LocationManager**: Location fetching
- **VoskWakeWordService**: Voice detection + alert trigger

---

## 🔧 Configuration Steps

### Step 1: Deploy Supabase Schema

1. Open your Supabase project: https://app.supabase.com
2. Go to **SQL Editor**
3. Open `supabase_schema.sql` from your project
4. Copy the entire content
5. Paste into SQL Editor
6. Click **Run**
7. Verify tables in **Table Editor**:
   - users
   - fcm_tokens
   - guardians
   - alert_history
   - alert_recipients

### Step 2: Firebase Setup

1. Go to Firebase Console: https://console.firebase.google.com
2. Create a new project or select existing
3. Click **Add app** → **Android**
4. Enter package name: `com.sriox.vasateysec`
5. Download `google-services.json`
6. Place file in: `app/google-services.json`
7. In Firebase Console:
   - Go to **Cloud Messaging**
   - Note down your Server Key (for Vercel if needed)

### Step 3: Google Maps API Key

1. Go to Google Cloud Console: https://console.cloud.google.com
2. Enable **Maps SDK for Android**
3. Go to **APIs & Services** → **Credentials**
4. Create API Key
5. Restrict key to Android apps
6. Add package name: `com.sriox.vasateysec`
7. Add SHA-1 fingerprint (get from Android Studio)
8. Copy the API key
9. Open `AndroidManifest.xml`
10. Replace `YOUR_GOOGLE_MAPS_API_KEY` with your actual key

```xml
<meta-data
    android:name="com.google.android.geo.API_KEY"
    android:value="AIzaSy..." />
```

### Step 4: Verify Supabase Credentials

Open `SupabaseClient.kt` and verify your credentials are correct:
```kotlin
private const val SUPABASE_URL = "https://hjxmjmdqvgiaeourpbbc.supabase.co"
private const val SUPABASE_ANON_KEY = "eyJhbGc..."
```

### Step 5: Build & Run

```bash
# Sync Gradle
./gradlew build

# Install on device
./gradlew installDebug

# Or use Android Studio
# Click Run button
```

---

## 📱 How to Use the App

### First Time Setup

1. **Sign Up**
   - Open app → Signup
   - Enter name, email, phone, password
   - Verify email (check Supabase Auth settings)

2. **Login**
   - Enter email and password
   - FCM token automatically registered

3. **Add Guardians**
   - Home → Add Guardian
   - Enter guardian's email
   - They'll receive alerts when you need help

4. **Start Listening**
   - Home → Start Listening Service
   - Grant microphone permission
   - Say "help me" to trigger alert

### Emergency Alert Flow

1. User says **"help me"**
2. App gets current location
3. Fetches user profile from Supabase
4. Gets guardian emails
5. Gets FCM tokens for guardians
6. Sends notifications via Vercel endpoint
7. Creates alert_history record
8. Creates alert_recipients records
9. Guardians receive notification
10. Click notification → Opens HelpRequestActivity
11. Shows user info + location on map
12. Can call or navigate to location

### Guardian Experience

1. Receive notification: "🚨 [Name] needs your help!"
2. Click notification
3. See user details:
   - Name
   - Email
   - Phone (clickable to call)
   - Location on map
4. Actions:
   - **Call**: Direct phone call
   - **Navigate**: Open in Google Maps
5. View in Alert History later

---

## 🧪 Testing Checklist

### Authentication
- [ ] Sign up new user
- [ ] Verify user in Supabase users table
- [ ] Login with credentials
- [ ] Auto-login on app restart
- [ ] Logout

### FCM Tokens
- [ ] Token saved on login
- [ ] Check fcm_tokens table in Supabase
- [ ] Token updates on reinstall
- [ ] Old tokens deactivated

### Guardians
- [ ] Add guardian by email
- [ ] View guardians list
- [ ] Remove guardian
- [ ] Check guardians table in Supabase

### Voice Detection
- [ ] Start listening service
- [ ] Say "help me"
- [ ] See "Emergency Alert Triggered" notification
- [ ] Check logs for location fetch
- [ ] Check logs for alert sending

### Notifications
- [ ] Guardian receives notification
- [ ] Notification shows correct info
- [ ] Click opens HelpRequestActivity
- [ ] Map shows correct location
- [ ] Call button works
- [ ] Navigate button works

### Alert History
- [ ] View sent alerts
- [ ] View received alerts (as guardian)
- [ ] Click alert opens details
- [ ] Timestamps display correctly

### Profile
- [ ] Edit name
- [ ] Edit phone
- [ ] Save changes
- [ ] Verify in Supabase

---

## 🔍 Troubleshooting

### Build Errors

**Error**: `google-services.json not found`
- **Fix**: Download from Firebase Console and place in `app/` folder

**Error**: `Duplicate class found`
- **Fix**: Clean and rebuild: `./gradlew clean build`

**Error**: `Maps API key not found`
- **Fix**: Add API key to AndroidManifest.xml

### Runtime Issues

**FCM Token not saving**
- Check Supabase connection
- Verify user is logged in
- Check logs for errors

**Location not working**
- Grant location permissions
- Enable location services on device
- Check GPS signal

**Voice detection not working**
- Grant microphone permission
- Check if model files exist in assets
- Verify service is running

**Notifications not received**
- Check FCM token in Supabase
- Verify Vercel endpoint is working
- Check guardian has app installed
- Test Vercel endpoint manually

### Database Issues

**RLS Policy errors**
- Verify schema was deployed correctly
- Check user is authenticated
- Review Supabase logs

**Guardian not linked**
- Guardian must sign up with same email
- Trigger will auto-link on signup

---

## 🚀 Vercel Endpoint

Your endpoint is already configured:
```
https://vasatey-notify-msg.vercel.app/api/sendNotification
```

### Test Endpoint Manually

```bash
curl -X POST https://vasatey-notify-msg.vercel.app/api/sendNotification \
  -H "Content-Type: application/json" \
  -d '{
    "token": "FCM_TOKEN_HERE",
    "title": "Test Alert",
    "body": "Testing notification",
    "fullName": "Test User",
    "email": "test@example.com",
    "phoneNumber": "+1234567890",
    "lastKnownLatitude": 37.7749,
    "lastKnownLongitude": -122.4194
  }'
```

---

## 📊 Database Schema Overview

```
users
├── id (UUID, references auth.users)
├── name
├── email
├── phone
└── timestamps

fcm_tokens
├── id
├── user_id → users.id
├── token (unique)
├── device_id
├── is_active
└── timestamps

guardians
├── id
├── user_id → users.id
├── guardian_email
├── guardian_user_id → users.id (auto-linked)
└── status

alert_history
├── id
├── user_id → users.id
├── user_name, email, phone
├── latitude, longitude
├── alert_type
└── status

alert_recipients
├── id
├── alert_id → alert_history.id
├── guardian_email
├── fcm_token
├── notification_sent
└── viewed_at
```

---

## 🎯 Next Steps

1. **Deploy Supabase Schema** ✓
2. **Add google-services.json** ✓
3. **Add Google Maps API Key** ✓
4. **Build and Test** ✓
5. **Add Test Guardians** ✓
6. **Test Emergency Flow** ✓

---

## 📞 Support

If you encounter issues:
1. Check logs in Android Studio Logcat
2. Verify Supabase tables and data
3. Test Vercel endpoint manually
4. Check Firebase Console for FCM logs
5. Review this guide again

---

## 🎉 You're All Set!

Your Vasatey Safety App is ready to protect users with:
- ✅ Voice-activated emergency alerts
- ✅ Real-time location sharing
- ✅ Guardian notification system
- ✅ Complete alert history
- ✅ Scalable architecture

Stay safe! 🛡️
