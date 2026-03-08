// Supabase Edge Function: send-cancellation-notification
// Sends FCM notification when user cancels an alert

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
    const { alertId, guardianEmail } = await req.json()
    
    console.log('📤 Sending cancellation notification for alert:', alertId)
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
      console.error('Guardian user not found:', guardianEmail)
      throw new Error('Guardian not found')
    }

    const { data: tokens, error: tokenError } = await supabaseClient
      .from('fcm_tokens')
      .select('token')
      .eq('user_id', guardianUser.id)
      .eq('is_active', true)

    if (tokenError || !tokens || tokens.length === 0) {
      console.error('No FCM token found for guardian')
      throw new Error('Guardian device not reachable')
    }

    const guardianToken = tokens[0].token
    console.log('Guardian token found:', guardianToken.substring(0, 20) + '...')

    // Send FCM notification to guardian
    await sendFCMNotification(guardianToken, alertId)

    console.log('✅ Cancellation notification sent successfully')

    return new Response(
      JSON.stringify({ 
        success: true,
        message: 'Cancellation notification sent'
      }),
      { 
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        status: 200 
      }
    )

  } catch (error) {
    console.error('❌ Error in send-cancellation-notification:', error)
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
        type: 'alert_cancelled',
        alertId: alertId,
        title: '✅ Alert Cancelled',
        body: 'The user has cancelled the alert. It was a false alarm.'
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
      hash: 'SHA-256'
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
    .replace(/=+$/, '')
  
  const jwt = `${signatureInput}.${jwtSignature}`
  
  // Exchange JWT for access token
  const tokenResponse = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded'
    },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${jwt}`
  })
  
  const tokenData = await tokenResponse.json()
  return tokenData.access_token
}

// Helper function to convert PEM to ArrayBuffer
function pemToArrayBuffer(pem: string): ArrayBuffer {
  const pemContents = pem
    .replace('-----BEGIN PRIVATE KEY-----', '')
    .replace('-----END PRIVATE KEY-----', '')
    .replace(/\s/g, '')
  
  const binaryString = atob(pemContents)
  const bytes = new Uint8Array(binaryString.length)
  for (let i = 0; i < binaryString.length; i++) {
    bytes[i] = binaryString.charCodeAt(i)
  }
  return bytes.buffer
}
