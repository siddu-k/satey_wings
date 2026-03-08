# 🚀 VASATEYSEC - Complete Supabase Setup Guide

## ✅ What This Schema Includes

This schema is **100% compatible** with your Android app and includes:

### Tables Created:
1. **users** - User profiles (name, email, phone)
2. **fcm_tokens** - Firebase push notification tokens
3. **guardians** - Guardian relationships
4. **alert_history** - Emergency alert records
5. **alert_recipients** - Who received which alerts
6. **notifications** - Push notification queue

### Features:
- ✅ Row Level Security (RLS) enabled
- ✅ Proper foreign key relationships
- ✅ Automatic timestamp updates
- ✅ Auto-linking guardians when they sign up
- ✅ Auto-deactivating old FCM tokens
- ✅ Syncing auth.users with public.users
- ✅ Performance indexes on all tables
- ✅ Proper permissions for authenticated users

---

## 📋 Step-by-Step Setup Instructions

### Step 1: Open Supabase SQL Editor
1. Go to https://app.supabase.com
2. Select your project: **dmnxjgekyxcmfkymsqlb**
3. Click on **SQL Editor** in the left sidebar
4. Click **New Query**

### Step 2: Run the Schema
1. Open the file: `WORKING_SUPABASE_SCHEMA.sql`
2. Copy the **ENTIRE** contents
3. Paste into the SQL Editor
4. Click **Run** (or press Ctrl+Enter)
5. Wait for "Success. No rows returned" message

### Step 3: Verify Tables Created
1. Go to **Table Editor** in the left sidebar
2. You should see these 6 tables:
   - users
   - fcm_tokens
   - guardians
   - alert_history
   - alert_recipients
   - notifications

### Step 4: Check RLS Policies
1. Click on any table (e.g., "users")
2. Go to the **Policies** tab
3. You should see multiple policies listed
4. All policies should show as "Enabled"

---

## 🧪 Testing Your Setup

### Test 1: Create a User Account
1. Open your app
2. Sign up with a new account
3. Go to Supabase → Table Editor → users
4. You should see your user record automatically created

### Test 2: Add a Guardian
1. In the app, go to "Add Guardian"
2. Enter a guardian's email
3. Click "Add"
4. Go to Supabase → Table Editor → guardians
5. You should see the guardian record with status "active"

### Test 3: Trigger an Alert
1. Say "help me" to trigger the wake word
2. Go to Supabase → Table Editor → alert_history
3. You should see the alert record
4. Check alert_recipients table for guardian notifications

---

## 🔧 Troubleshooting

### Issue: "No guardians found" error
**Solution:**
1. Make sure you've added at least one guardian in the app
2. Check Supabase → guardians table to verify the record exists
3. Verify the guardian has status = 'active'
4. Verify user_id matches your current user ID

### Issue: JSON parsing error
**Solution:**
- This schema fixes the JSON parsing errors
- The RLS policies now properly allow queries to return empty arrays instead of errors
- Make sure you ran the ENTIRE schema file

### Issue: "User not logged in"
**Solution:**
1. Make sure you're logged in to the app
2. Check if auth.uid() is set by running this in SQL Editor:
   ```sql
   SELECT auth.uid();
   ```
3. If null, log out and log back in

### Issue: FCM tokens not saving
**Solution:**
1. Check if Firebase is properly configured in your app
2. Verify google-services.json is in the app folder
3. Check Supabase → fcm_tokens table to see if tokens are being saved

---

## 📊 Understanding the Schema

### How Guardians Work:
1. User A adds User B's email as a guardian
2. Record created in `guardians` table with:
   - `user_id` = User A's ID
   - `guardian_email` = User B's email
   - `guardian_user_id` = NULL (initially)
3. When User B signs up, the trigger automatically links:
   - `guardian_user_id` = User B's ID

### How Alerts Work:
1. User triggers "help me"
2. Record created in `alert_history` with user info and location
3. App queries `guardians` table to find all guardians
4. For each guardian, app creates record in `alert_recipients`
5. Push notifications sent via `notifications` table

### How RLS Protects Data:
- Users can only see their own guardians
- Users can only create alerts for themselves
- Guardians can only see alerts sent to them
- All queries are filtered by auth.uid()

---

## 🎯 Key Differences from Old Schema

### Fixed Issues:
1. ✅ **RLS Policies** - Now properly configured to allow empty results
2. ✅ **Foreign Keys** - All relationships properly defined
3. ✅ **Permissions** - Correct grants for authenticated users
4. ✅ **Indexes** - Added for better query performance
5. ✅ **Triggers** - Auto-sync between auth.users and public.users
6. ✅ **Notifications Table** - Added for push notification queue

### Removed Issues:
- ❌ No more "Expected start of array" errors
- ❌ No more RLS blocking legitimate queries
- ❌ No more missing foreign key constraints
- ❌ No more permission denied errors

---

## 📱 App Compatibility

This schema is **100% compatible** with:
- ✅ SignupActivity.kt
- ✅ LoginActivity.kt
- ✅ HomeActivity.kt
- ✅ AddGuardianActivity.kt
- ✅ AlertHistoryActivity.kt
- ✅ EditProfileActivity.kt
- ✅ VoskWakeWordService.kt
- ✅ AlertManager.kt
- ✅ FCMTokenManager.kt
- ✅ All data models in models/

---

## 🔐 Security Features

1. **Row Level Security (RLS)** - Enabled on all tables
2. **User Isolation** - Users can only access their own data
3. **Guardian Privacy** - Guardians only see alerts sent to them
4. **Secure Functions** - All functions use SECURITY DEFINER
5. **Proper Grants** - Minimal permissions for anon role

---

## 📞 Support

If you encounter any issues:
1. Check the Troubleshooting section above
2. Verify all tables were created in Table Editor
3. Check RLS policies are enabled
4. Review app logs using: `adb logcat -s AlertManager:D`

---

## ✨ You're All Set!

Your Supabase database is now fully configured and ready to use with your VasateySec app!

**Next Steps:**
1. Run the schema in Supabase SQL Editor
2. Rebuild and install your app
3. Sign up / Log in
4. Add guardians
5. Test the "help me" feature

Good luck! 🎉
