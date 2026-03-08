-- Migration: Add token validation tracking to fcm_tokens table
-- Purpose: Track when tokens were last confirmed working to identify stale tokens

-- Add last_validated_at column
ALTER TABLE fcm_tokens 
ADD COLUMN IF NOT EXISTS last_validated_at TIMESTAMPTZ;

-- Create index for cleanup queries
CREATE INDEX IF NOT EXISTS idx_fcm_tokens_last_validated 
ON fcm_tokens(last_validated_at DESC);

-- Add comment for documentation
COMMENT ON COLUMN fcm_tokens.last_validated_at IS 'Timestamp when token was last confirmed working (successful notification sent)';
