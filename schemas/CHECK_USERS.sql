-- ============================================
-- CHECK USERS AND GUARDIANS
-- ============================================
-- Run this to see all users and guardian relationships
-- ============================================

-- 1. Show all users
SELECT 
    id,
    name,
    email,
    phone,
    created_at
FROM users
ORDER BY created_at DESC;

-- 2. Show all guardians with user info
SELECT 
    g.id as guardian_record_id,
    g.user_id as protected_person_id,
    u1.name as protected_person_name,
    u1.email as protected_person_email,
    g.guardian_email,
    g.guardian_user_id,
    u2.name as guardian_name,
    g.status
FROM guardians g
LEFT JOIN users u1 ON u1.id = g.user_id
LEFT JOIN users u2 ON u2.id = g.guardian_user_id
ORDER BY g.created_at DESC;

-- 3. Check if guardian email exists in users table
SELECT 
    email,
    name,
    id
FROM users
WHERE email = 'sridata.t59@gmail.com';

-- ============================================
-- SOLUTION:
-- ============================================
-- If the guardian email does NOT appear in users table:
--   → The guardian needs to sign up in the app first
--
-- If the guardian email DOES appear in users table:
--   → Run FIX_GUARDIAN_LINK.sql to link them
-- ============================================
