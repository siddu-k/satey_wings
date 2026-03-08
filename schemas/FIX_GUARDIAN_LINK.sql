-- ============================================
-- FIX GUARDIAN USER ID LINKING
-- ============================================
-- This will manually link guardians to their user accounts
-- Run this in Supabase SQL Editor
-- ============================================

-- Step 1: Check current state
SELECT 
    g.id,
    g.user_id,
    g.guardian_email,
    g.guardian_user_id,
    u.id as actual_user_id,
    u.name as guardian_name
FROM guardians g
LEFT JOIN users u ON u.email = g.guardian_email;

-- Step 2: Update guardian_user_id for guardians who have signed up
UPDATE guardians g
SET guardian_user_id = u.id
FROM users u
WHERE g.guardian_email = u.email
AND g.guardian_user_id IS NULL;

-- Step 3: Verify the update
SELECT 
    g.id,
    g.user_id,
    g.guardian_email,
    g.guardian_user_id,
    g.status
FROM guardians g;

-- ============================================
-- If guardian hasn't signed up yet:
-- ============================================
-- The guardian needs to:
-- 1. Download the app
-- 2. Sign up with email: sridata.t59@gmail.com
-- 3. Then this will auto-link via trigger
-- ============================================
