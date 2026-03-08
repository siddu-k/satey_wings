-- Migration: Add user self-location columns to users table
-- Purpose: Store each user's latest location directly in their profile

-- Add location columns to users table
ALTER TABLE users 
ADD COLUMN IF NOT EXISTS last_latitude DOUBLE PRECISION,
ADD COLUMN IF NOT EXISTS last_longitude DOUBLE PRECISION,
ADD COLUMN IF NOT EXISTS last_location_updated_at TIMESTAMPTZ;

-- Create index for location queries
CREATE INDEX IF NOT EXISTS idx_users_last_location_updated 
ON users(last_location_updated_at DESC);

-- Add comment for documentation
COMMENT ON COLUMN users.last_latitude IS 'User''s most recent latitude position';
COMMENT ON COLUMN users.last_longitude IS 'User''s most recent longitude position';
COMMENT ON COLUMN users.last_location_updated_at IS 'Timestamp when location was last updated';
