-- Add wake_word column to users table
-- Run this in your Supabase SQL Editor

ALTER TABLE public.users 
ADD COLUMN IF NOT EXISTS wake_word TEXT DEFAULT 'help me';

-- Add comment
COMMENT ON COLUMN public.users.wake_word IS 'Custom wake word/phrase for emergency alerts';

-- Update existing users to have default wake word
UPDATE public.users 
SET wake_word = 'help me' 
WHERE wake_word IS NULL;
