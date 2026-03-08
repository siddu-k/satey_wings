-- Clean up all FCM tokens from database
-- Run this in Supabase SQL Editor to start fresh

-- Show current tokens before deletion
SELECT 
    id,
    user_id,
    LEFT(token, 30) || '...' as token_preview,
    device_name,
    is_active,
    created_at,
    last_used_at
FROM fcm_tokens
ORDER BY created_at DESC;

-- Delete all FCM tokens
DELETE FROM fcm_tokens;

-- Verify deletion
SELECT COUNT(*) as remaining_tokens FROM fcm_tokens;

-- Expected result: remaining_tokens = 0
