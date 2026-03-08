// api/sendNotification.js
import admin from 'firebase-admin';

// Initialize Firebase Admin (only once)
if (!admin.apps.length) {
  try {
    const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount)
    });
    console.log('Firebase Admin initialized successfully');
  } catch (error) {
    console.error('Failed to initialize Firebase Admin:', error);
  }
}

export default async function handler(req, res) {
  // Only allow POST requests
  if (req.method !== 'POST') {
    return res.status(405).json({ error: 'Method not allowed' });
  }

  try {
    const {
      token,
      title,
      body,
      email,
      isSelfAlert,
      fullName,
      phoneNumber,
      lastKnownLatitude,
      lastKnownLongitude,
      frontPhotoUrl,
      backPhotoUrl,
      alertId,
      // New fields for contact requests
      type,
      requestId,
      fromName,
      fromPhone
    } = req.body;

    // Validate required fields
    if (!token || !title || !body || !email) {
      return res.status(400).json({
        error: 'Missing required fields',
        required: ['token', 'title', 'body', 'email'],
        received: {
          token: !!token,
          title: !!title,
          body: !!body,
          email: !!email
        }
      });
    }

    // LOG EVERYTHING for debugging
    console.log(`========================================`);
    console.log(`Sending notification to ${email}`);
    console.log(`Type: ${type || 'alert'}`);
    console.log(`Alert ID / Request ID: ${alertId || requestId}`);
    console.log(`Full request body:`, JSON.stringify(req.body, null, 2));
    console.log(`========================================`);

    // Convert location to string, handle null/undefined properly
    const latStr = (lastKnownLatitude !== null && lastKnownLatitude !== undefined)
      ? String(lastKnownLatitude)
      : '';
    const lonStr = (lastKnownLongitude !== null && lastKnownLongitude !== undefined)
      ? String(lastKnownLongitude)
      : '';
    const frontPhoto = frontPhotoUrl || '';
    const backPhoto = backPhotoUrl || '';

    // Send FCM notification with ONLY data payload
    const message = {
      token: token,
      data: {
        type: type || 'alert',
        title: title || '',
        body: body || '',
        email: email || '',
        fullName: fullName || '',
        phoneNumber: phoneNumber || '',
        lastKnownLatitude: latStr,
        lastKnownLongitude: lonStr,
        frontPhotoUrl: frontPhoto,
        backPhotoUrl: backPhoto,
        isSelfAlert: String(isSelfAlert || false),
        alertId: alertId || '',
        // Contact request specific fields
        requestId: requestId || '',
        fromName: fromName || '',
        fromPhone: fromPhone || ''
      },
      android: {
        priority: 'high'
      }
    };

    console.log('FCM message data being sent:', JSON.stringify(message.data, null, 2));

    const response = await admin.messaging().send(message);
    console.log('FCM notification sent successfully:', response);

    return res.status(200).json({
      success: true,
      messageId: response,
      recipient: email,
      type: type || 'alert',
      alertId: alertId,
      requestId: requestId
    });

  } catch (error) {
    console.error('Error sending notification:', error);
    return res.status(500).json({
      success: false,
      error: error.message || 'Failed to send notification',
      code: error.code
    });
  }
}

export const config = {
  api: {
    bodyParser: {
      sizeLimit: '1mb',
    },
  },
}
