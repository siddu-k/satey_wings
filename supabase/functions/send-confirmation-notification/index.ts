// Supabase Edge Function: send-confirmation-notification
// Sends FCM notification when guardian confirms an alert

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
    const { alertId, guardianEmail, guardianUserId } = await req.json()
    
    console.log('📤 Sending confirmation notification for alert:', alertId)
    console.log('Guardian:', guardianEmail)

    // Initialize Supabase client
    const supabaseClient = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )

    // Get alert details
    const { data: alert, error: alertError } = await supabaseClient
      .from('alert_history')
      .select('*')
      .eq('id', alertId)
      .single()

    if (alertError || !alert) {
      console.error('Error fetching alert:', alertError)
      throw new Error('Alert not found')
    }

    console.log('Alert found for user:', alert.user_id)

    // Get user's FCM token
    const { data: tokens, error: tokenError } = await supabaseClient
      .from('fcm_tokens')
      .select('token')
      .eq('user_id', alert.user_id)
      .eq('is_active', true)

    if (tokenError || !tokens || tokens.length === 0) {
      console.error('No FCM token found for user:', alert.user_id)
      throw new Error('User device not reachable')
    }

    const userToken = tokens[0].token
    console.log('User token found:', userToken.substring(0, 20) + '...')

    // Calculate expiry time (30 seconds from now)
    const expiresAt = new Date(Date.now() + 30000).toISOString()

    // Create confirmation record
    const { error: confirmError } = await supabaseClient
      .from('alert_confirmations')
      .insert({
        alert_id: alertId,
        guardian_email: guardianEmail,
        guardian_user_id: guardianUserId,
        confirmation_status: 'confirmed',
        confirmed_at: new Date().toISOString(),
        expires_at: expiresAt
      })

    if (confirmError) {
      console.error('Error creating confirmation:', confirmError)
      throw confirmError
    }

    console.log('Confirmation record created, expires at:', expiresAt)

    // Send FCM notification to user
    const fcmResult = await sendFCMNotification(
      userToken,
      alertId,
      guardianEmail
    )

    console.log('✅ Confirmation notification sent successfully')

    return new Response(
      JSON.stringify({ 
        success: true,
        message: 'Confirmation notification sent',
        expiresAt: expiresAt
      }),
      { 
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        status: 200 
      }
    )

  } catch (error) {
    console.error('❌ Error in send-confirmation-notification:', error)
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
async function sendFCMNotification(
  fcmToken: string,
  alertId: string,
  guardianEmail: string
) {
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
        type: 'alert_confirmation',
        alertId: alertId,
        guardianEmail: guardianEmail,
        title: '⚠️ Alert Confirmation',
        body: `Guardian ${guardianEmail} confirmed your alert. You have 30 seconds to cancel if this is a false alarm.`
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
