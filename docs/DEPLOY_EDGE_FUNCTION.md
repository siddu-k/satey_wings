# Deploy Edge Function to Supabase

## ✅ Fixed Issues:
1. Changed `guardian_email` to `guardianEmail` in the Edge Function
2. This matches what the Android app is now sending

## 🚀 Deploy to Supabase

### Option 1: Using Supabase CLI (Recommended)

1. **Install Supabase CLI** (if not already installed):
```bash
npm install -g supabase
```

2. **Login to Supabase:**
```bash
supabase login
```

3. **Link your project:**
```bash
cd c:\siddu\vasateysec
supabase link --project-ref acgsmcxmesvsftzugeik
```

4. **Deploy the function:**
```bash
supabase functions deploy request-live-locations
```

### Option 2: Manual Deployment via Dashboard

1. **Go to Supabase Dashboard:**
   - Open: https://app.supabase.com
   - Select your project: `acgsmcxmesvsftzugeik`

2. **Navigate to Edge Functions:**
   - Click "Edge Functions" in the left sidebar
   - Find "request-live-locations" function

3. **Update the function:**
   - Click on the function name
   - Click "Edit Function"
   - Copy the entire content from: `c:\siddu\vasateysec\supabase\functions\request-live-locations\index.ts`
   - Paste it into the editor
   - Click "Deploy"

### Option 3: Copy-Paste the Fixed Code

If you prefer, here's the complete fixed code to paste:

```typescript
// Supabase Edge Function: request-live-locations
// This function sends FCM notifications to request live locations from users

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

serve(async (req) => {
  // Handle CORS preflight requests
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders })
  }

  try {
    // Get guardian's email from request
    const { guardianEmail } = await req.json()
    
    console.log('📍 Live location request from guardian:', guardianEmail)

    // Initialize Supabase client
    const supabaseClient = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )

    // Step 1: Find all users who added this guardian
    const { data: guardianRelations, error: guardianError } = await supabaseClient
      .from('guardians')
      .select('user_id')
      .eq('guardian_email', guardianEmail)

    if (guardianError) {
      console.error('Error fetching guardians:', guardianError)
      throw guardianError
    }

    console.log(`📍 Found ${guardianRelations?.length || 0} users to request location from`)

    // Step 2: For each user, get their FCM tokens and send request
    const userIds = guardianRelations?.map(g => g.user_id) || []
    
    for (const userId of userIds) {
      // Get user's active FCM tokens
      const { data: tokens, error: tokenError } = await supabaseClient
        .from('fcm_tokens')
        .select('token')
        .eq('user_id', userId)
        .eq('is_active', true)

      if (tokenError) {
        console.error(`Error fetching tokens for user ${userId}:`, tokenError)
        continue
      }

      console.log(`📍 User ${userId} has ${tokens?.length || 0} active tokens`)

      // Send FCM notification to each token
      for (const tokenData of tokens || []) {
        try {
          await sendFCMLocationRequest(tokenData.token, guardianEmail)
          console.log(`✅ Location request sent to token: ${tokenData.token.substring(0, 20)}...`)
        } catch (error) {
          console.error(`❌ Error sending FCM to token:`, error)
        }
      }
    }

    return new Response(
      JSON.stringify({ 
        success: true, 
        users_requested: userIds.length,
        message: `Location requests sent to ${userIds.length} users`
      }),
      { 
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        status: 200 
      }
    )

  } catch (error) {
    console.error('❌ Error in request-live-locations:', error)
    return new Response(
      JSON.stringify({ error: error.message }),
      { 
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        status: 400 
      }
    )
  }
})

// Helper function to send FCM notification
async function sendFCMLocationRequest(fcmToken: string, guardianEmail: string) {
  const FCM_SERVER_KEY = Deno.env.get('FCM_SERVER_KEY')
  
  if (!FCM_SERVER_KEY) {
    throw new Error('FCM_SERVER_KEY not configured')
  }

  const message = {
    to: fcmToken,
    data: {
      type: 'location_request',
      guardianEmail: guardianEmail
    },
    priority: 'high'
  }

  const response = await fetch('https://fcm.googleapis.com/fcm/send', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `key=${FCM_SERVER_KEY}`
    },
    body: JSON.stringify(message)
  })

  if (!response.ok) {
    const error = await response.text()
    throw new Error(`FCM request failed: ${error}`)
  }

  return await response.json()
}
```

## ✅ After Deployment

1. **Test the function:**
   - Open your guardian app
   - Go to Track screen
   - Check logs for: `📤 Supabase function called: 200` (should be 200, not 500)

2. **Expected logs:**
```
📤 Supabase function called: 200
📤 Response: {"success":true,"users_requested":2,"message":"Location requests sent to 2 users"}
```

3. **On user device, you should see:**
```
VasateyFCMService: Message received from: ...
VasateyFCMService: Message data payload: {type=location_request, guardianEmail=sridatta.k99@gmail.com}
LiveLocationHelper: 📍 LOCATION REQUEST RECEIVED
```

## 🔧 Verify Deployment

After deploying, you can test the function directly:

```bash
curl -X POST https://acgsmcxmesvsftzugeik.supabase.co/functions/v1/request-live-locations \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_ANON_KEY" \
  -d '{"guardianEmail": "sridatta.k99@gmail.com"}'
```

Expected response:
```json
{
  "success": true,
  "users_requested": 2,
  "message": "Location requests sent to 2 users"
}
```
