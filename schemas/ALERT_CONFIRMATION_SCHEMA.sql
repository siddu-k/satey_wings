-- ============================================
-- ALERT CONFIRMATION SYSTEM - DATABASE SCHEMA
-- ============================================
-- Add this to your existing Supabase database
-- ============================================

-- 1. ALERT CONFIRMATIONS TABLE
-- Tracks guardian confirmations and user cancellations
CREATE TABLE IF NOT EXISTS public.alert_confirmations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  alert_id UUID NOT NULL REFERENCES public.alert_history(id) ON DELETE CASCADE,
  guardian_email TEXT NOT NULL,
  guardian_user_id UUID REFERENCES public.users(id) ON DELETE SET NULL,
  confirmation_status TEXT DEFAULT 'pending' CHECK (confirmation_status IN ('pending', 'confirmed', 'cancelled', 'expired')),
  confirmed_at TIMESTAMP WITH TIME ZONE,
  cancelled_at TIMESTAMP WITH TIME ZONE,
  expires_at TIMESTAMP WITH TIME ZONE,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  UNIQUE(alert_id, guardian_email)
);

-- 2. Add cancel_password field to users table
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS cancel_password TEXT;

-- 3. Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_alert_confirmations_alert_id ON public.alert_confirmations(alert_id);
CREATE INDEX IF NOT EXISTS idx_alert_confirmations_status ON public.alert_confirmations(confirmation_status);
CREATE INDEX IF NOT EXISTS idx_alert_confirmations_expires_at ON public.alert_confirmations(expires_at);

-- 4. Enable RLS on alert_confirmations
ALTER TABLE public.alert_confirmations ENABLE ROW LEVEL SECURITY;

-- 5. RLS Policies for alert_confirmations

-- Allow users to view confirmations for their own alerts
CREATE POLICY "alert_confirmations_select_own_alerts" ON public.alert_confirmations
  FOR SELECT USING (
    EXISTS (
      SELECT 1 FROM public.alert_history
      WHERE alert_history.id = alert_confirmations.alert_id
      AND alert_history.user_id = auth.uid()
    )
  );

-- Allow guardians to view their confirmations
CREATE POLICY "alert_confirmations_select_as_guardian" ON public.alert_confirmations
  FOR SELECT USING (auth.uid() = guardian_user_id);

-- Allow guardians to insert confirmations
CREATE POLICY "alert_confirmations_insert_guardian" ON public.alert_confirmations
  FOR INSERT WITH CHECK (auth.uid() = guardian_user_id);

-- Allow users to update their own alert confirmations (for cancellation)
CREATE POLICY "alert_confirmations_update_own_alerts" ON public.alert_confirmations
  FOR UPDATE USING (
    EXISTS (
      SELECT 1 FROM public.alert_history
      WHERE alert_history.id = alert_confirmations.alert_id
      AND alert_history.user_id = auth.uid()
    )
  );

-- Allow guardians to update their confirmations
CREATE POLICY "alert_confirmations_update_guardian" ON public.alert_confirmations
  FOR UPDATE USING (auth.uid() = guardian_user_id);

-- 6. Function to auto-expire confirmations after 30 seconds
CREATE OR REPLACE FUNCTION public.expire_old_confirmations()
RETURNS void AS $$
BEGIN
  UPDATE public.alert_confirmations
  SET confirmation_status = 'expired'
  WHERE confirmation_status = 'confirmed'
  AND expires_at < NOW()
  AND confirmation_status != 'cancelled';
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 7. Grant permissions
GRANT ALL ON public.alert_confirmations TO authenticated;
GRANT SELECT ON public.alert_confirmations TO anon;

-- ============================================
-- SETUP COMPLETE!
-- ============================================
-- Run this SQL in Supabase SQL Editor
-- ============================================
