// Supabase Edge Function: send-expiry-notification
// Sends FCM notification when user does NOT cancel alert (timer expires)

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
    const { alertId, guardianEmail, expired } = await req.json()
    
    console.log('📤 Sending EXPIRY notification for alert:', alertId)
    console.log('To guardian:', guardianEmail)

    // Initialize Supabase client
    const supabaseClient = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )

    // Get guardian's user ID and FCM token
    const { data: guardianUser, error: userError } = await supabaseClient
      .from('users')
      .select('id')
      .eq('email', guardianEmail)
      .single()

    if (userError || !guardianUser) {
      console.error('Guardian user not found:', userError)
      return new Response(
        JSON.stringify({ error: 'Guardian not found' }),
        { headers: { ...corsHeaders, 'Content-Type': 'application/json' }, status: 404 }
      )
    }

    // Get guardian's active FCM token
    const { data: tokens, error: tokenError } = await supabaseClient
      .from('fcm_tokens')
      .select('token')
      .eq('user_id', guardianUser.id)
      .eq('is_active', true)
      .limit(1)

    if (tokenError || !tokens || tokens.length === 0) {
      console.error('No active FCM token found for guardian')
      return new Response(
        JSON.stringify({ error: 'No FCM token found' }),
        { headers: { ...corsHeaders, 'Content-Type': 'application/json' }, status: 404 }
      )
    }

    const fcmToken = tokens[0].token

    // Send FCM notification
    await sendFCMNotification(fcmToken, alertId)

    console.log('✅ Expiry notification sent successfully')

    return new Response(
      JSON.stringify({ 
        success: true,
        message: 'Expiry notification sent',
        guardianEmail: guardianEmail
      }),
      { 
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        status: 200 
      }
    )

  } catch (error) {
    console.error('❌ Error in send-expiry-notification:', error)
    return new Response(
      JSON.stringify({ error: error.message }),
      { 
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        status: 400 
      }
    )
  }
})

// Helper function to send FCM notification using V1 API
async function sendFCMNotification(fcmToken: string, alertId: string) {
  const serviceAccountJson = Deno.env.get('FIREBASE_SERVICE_ACCOUNT')
  
  if (!serviceAccountJson) {
    throw new Error('FIREBASE_SERVICE_ACCOUNT not configured')
  }

  const serviceAccount = JSON.parse(serviceAccountJson)
  const projectId = serviceAccount.project_id

  // Get OAuth2 access token
  const accessToken = await getAccessToken(serviceAccount)

  const message = {
    message: {
      token: fcmToken,
      data: {
        type: 'alert_not_cancelled',
        alertId: alertId,
        title: '🚨 Alert Still Active',
        body: 'User did not cancel the alert. This is a real emergency!'
      },
      android: {
        priority: 'high'
      }
    }
  }

  const response = await fetch(
    `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${accessToken}`
      },
      body: JSON.stringify(message)
    }
  )

  if (!response.ok) {
    const error = await response.text()
    throw new Error(`FCM request failed: ${error}`)
  }

  return await response.json()
}

// Helper function to get OAuth2 access token for FCM V1
async function getAccessToken(serviceAccount: any) {
  const jwtHeader = btoa(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))
  const now = Math.floor(Date.now() / 1000)
  const jwtClaimSet = {
    iss: serviceAccount.client_email,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    exp: now + 3600,
    iat: now
  }
  const jwtClaimSetEncoded = btoa(JSON.stringify(jwtClaimSet))

  const signatureInput = `${jwtHeader}.${jwtClaimSetEncoded}`
  
  // Import private key
  const privateKey = await crypto.subtle.importKey(
    'pkcs8',
    pemToArrayBuffer(serviceAccount.private_key),
    {
      name: 'RSASSA-PKCS1-v1_5',
      hash: 'SHA-256',
    },
    false,
    ['sign']
  )

  // Sign the JWT
  const signature = await crypto.subtle.sign(
    'RSASSA-PKCS1-v1_5',
    privateKey,
    new TextEncoder().encode(signatureInput)
  )

  const jwtSignature = btoa(String.fromCharCode(...new Uint8Array(signature)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=/g, '')

  const jwt = `${signatureInput}.${jwtSignature}`

  // Exchange JWT for access token
  const tokenResponse = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${jwt}`
  })

  const tokenData = await tokenResponse.json()
  return tokenData.access_token
}

function pemToArrayBuffer(pem: string): ArrayBuffer {
  const b64 = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, '')
    .replace(/-----END PRIVATE KEY-----/, '')
    .replace(/\s/g, '')
  const binary = atob(b64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes.buffer
}
