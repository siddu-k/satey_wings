-- ============================================
-- VASATEYSEC - 100% WORKING SUPABASE SCHEMA
-- ============================================
-- This schema is specifically designed for your Android app
-- Copy and paste this ENTIRE file into Supabase SQL Editor and run it
-- ============================================

-- ============================================
-- STEP 1: CREATE TABLES
-- ============================================

-- 1. USERS TABLE
-- Stores user profile information
CREATE TABLE IF NOT EXISTS public.users (
  id UUID PRIMARY KEY,
  name TEXT NOT NULL,
  email TEXT NOT NULL UNIQUE,
  phone TEXT,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 2. FCM TOKENS TABLE
-- Stores Firebase Cloud Messaging tokens for push notifications
CREATE TABLE IF NOT EXISTS public.fcm_tokens (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
  token TEXT NOT NULL UNIQUE,
  device_id TEXT,
  device_name TEXT,
  platform TEXT DEFAULT 'android' CHECK (platform IN ('android', 'ios')),
  is_active BOOLEAN DEFAULT true,
  last_used_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 3. GUARDIANS TABLE
-- Stores guardian relationships (who protects whom)
CREATE TABLE IF NOT EXISTS public.guardians (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
  guardian_email TEXT NOT NULL,
  guardian_user_id UUID REFERENCES public.users(id) ON DELETE SET NULL,
  status TEXT DEFAULT 'active' CHECK (status IN ('active', 'inactive')),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  UNIQUE(user_id, guardian_email)
);

-- 4. ALERT HISTORY TABLE
-- Stores all emergency alerts
CREATE TABLE IF NOT EXISTS public.alert_history (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
  user_name TEXT NOT NULL,
  user_email TEXT NOT NULL,
  user_phone TEXT NOT NULL,
  latitude DOUBLE PRECISION,
  longitude DOUBLE PRECISION,
  location_accuracy REAL,
  alert_type TEXT DEFAULT 'voice_help' CHECK (alert_type IN ('voice_help', 'manual', 'emergency')),
  status TEXT DEFAULT 'sent' CHECK (status IN ('sent', 'acknowledged', 'resolved')),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 5. ALERT RECIPIENTS TABLE
-- Tracks which guardians received each alert
CREATE TABLE IF NOT EXISTS public.alert_recipients (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  alert_id UUID NOT NULL REFERENCES public.alert_history(id) ON DELETE CASCADE,
  guardian_email TEXT NOT NULL,
  guardian_user_id UUID REFERENCES public.users(id) ON DELETE SET NULL,
  fcm_token TEXT,
  notification_sent BOOLEAN DEFAULT false,
  notification_delivered BOOLEAN DEFAULT false,
  viewed_at TIMESTAMP WITH TIME ZONE,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 6. NOTIFICATIONS TABLE (for queuing push notifications)
CREATE TABLE IF NOT EXISTS public.notifications (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  alert_id UUID REFERENCES public.alert_history(id) ON DELETE CASCADE,
  token TEXT NOT NULL,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  recipient_email TEXT NOT NULL,
  is_self_alert BOOLEAN DEFAULT false,
  status TEXT DEFAULT 'pending' CHECK (status IN ('pending', 'sent', 'failed')),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  sent_at TIMESTAMP WITH TIME ZONE
);

-- 7. LIVE LOCATIONS TABLE (for real-time location tracking)
-- Stores current location of users when requested by guardians
CREATE TABLE IF NOT EXISTS public.live_locations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
  latitude DOUBLE PRECISION NOT NULL,
  longitude DOUBLE PRECISION NOT NULL,
  accuracy REAL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  UNIQUE(user_id)
);

-- ============================================
-- STEP 2: CREATE INDEXES FOR PERFORMANCE
-- ============================================

-- Users indexes
CREATE INDEX IF NOT EXISTS idx_users_email ON public.users(email);
CREATE INDEX IF NOT EXISTS idx_users_created_at ON public.users(created_at);

-- FCM tokens indexes
CREATE INDEX IF NOT EXISTS idx_fcm_tokens_user_id ON public.fcm_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_fcm_tokens_token ON public.fcm_tokens(token);
CREATE INDEX IF NOT EXISTS idx_fcm_tokens_active ON public.fcm_tokens(user_id, is_active);

-- Guardians indexes
CREATE INDEX IF NOT EXISTS idx_guardians_user_id ON public.guardians(user_id);
CREATE INDEX IF NOT EXISTS idx_guardians_email ON public.guardians(guardian_email);
CREATE INDEX IF NOT EXISTS idx_guardians_user_id_status ON public.guardians(user_id, status);

-- Alert history indexes
CREATE INDEX IF NOT EXISTS idx_alert_history_user_id ON public.alert_history(user_id);
CREATE INDEX IF NOT EXISTS idx_alert_history_created_at ON public.alert_history(created_at DESC);

-- Alert recipients indexes
CREATE INDEX IF NOT EXISTS idx_alert_recipients_alert_id ON public.alert_recipients(alert_id);
CREATE INDEX IF NOT EXISTS idx_alert_recipients_guardian_user_id ON public.alert_recipients(guardian_user_id);

-- Notifications indexes
CREATE INDEX IF NOT EXISTS idx_notifications_status ON public.notifications(status);
CREATE INDEX IF NOT EXISTS idx_notifications_created_at ON public.notifications(created_at);

-- Live locations indexes
CREATE INDEX IF NOT EXISTS idx_live_locations_user_id ON public.live_locations(user_id);
CREATE INDEX IF NOT EXISTS idx_live_locations_updated_at ON public.live_locations(updated_at DESC);

-- ============================================
-- STEP 3: ENABLE ROW LEVEL SECURITY (RLS)
-- ============================================

ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.fcm_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.guardians ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.alert_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.alert_recipients ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.live_locations ENABLE ROW LEVEL SECURITY;

-- ============================================
-- STEP 4: CREATE RLS POLICIES
-- ============================================

-- USERS TABLE POLICIES
-- Allow users to read all users (for guardian lookups)
CREATE POLICY "users_select_all" ON public.users
  FOR SELECT USING (true);

-- Allow users to insert their own profile
CREATE POLICY "users_insert_own" ON public.users
  FOR INSERT WITH CHECK (auth.uid() = id);

-- Allow users to update their own profile
CREATE POLICY "users_update_own" ON public.users
  FOR UPDATE USING (auth.uid() = id);

-- FCM TOKENS TABLE POLICIES
-- Allow users to manage their own FCM tokens
CREATE POLICY "fcm_tokens_all_own" ON public.fcm_tokens
  FOR ALL USING (auth.uid() = user_id);

-- Allow reading tokens for guardians (needed for alerts)
CREATE POLICY "fcm_tokens_select_for_alerts" ON public.fcm_tokens
  FOR SELECT USING (true);

-- GUARDIANS TABLE POLICIES
-- Allow users to manage their own guardians
CREATE POLICY "guardians_all_own" ON public.guardians
  FOR ALL USING (auth.uid() = user_id);

-- Allow guardians to view assignments where they are the guardian
CREATE POLICY "guardians_select_as_guardian" ON public.guardians
  FOR SELECT USING (
    auth.uid() = guardian_user_id OR
    EXISTS (SELECT 1 FROM public.users WHERE id = auth.uid() AND email = guardian_email)
  );

-- ALERT HISTORY TABLE POLICIES
-- Allow users to view their own alerts
CREATE POLICY "alert_history_select_own" ON public.alert_history
  FOR SELECT USING (auth.uid() = user_id);

-- Allow users to create their own alerts
CREATE POLICY "alert_history_insert_own" ON public.alert_history
  FOR INSERT WITH CHECK (auth.uid() = user_id);

-- Allow guardians to view alerts sent to them
CREATE POLICY "alert_history_select_as_guardian" ON public.alert_history
  FOR SELECT USING (
    EXISTS (
      SELECT 1 FROM public.alert_recipients
      WHERE alert_recipients.alert_id = alert_history.id
      AND alert_recipients.guardian_user_id = auth.uid()
    )
  );

-- ALERT RECIPIENTS TABLE POLICIES
-- Allow users to view recipients of their own alerts
CREATE POLICY "alert_recipients_select_own_alerts" ON public.alert_recipients
  FOR SELECT USING (
    EXISTS (
      SELECT 1 FROM public.alert_history
      WHERE alert_history.id = alert_recipients.alert_id
      AND alert_history.user_id = auth.uid()
    )
  );

-- Allow guardians to view their received alerts
CREATE POLICY "alert_recipients_select_as_guardian" ON public.alert_recipients
  FOR SELECT USING (auth.uid() = guardian_user_id);

-- Allow system to insert alert recipients
CREATE POLICY "alert_recipients_insert_system" ON public.alert_recipients
  FOR INSERT WITH CHECK (true);

-- Allow guardians to update viewed status
CREATE POLICY "alert_recipients_update_viewed" ON public.alert_recipients
  FOR UPDATE USING (auth.uid() = guardian_user_id);

-- NOTIFICATIONS TABLE POLICIES
-- Allow system to manage notifications
CREATE POLICY "notifications_all_system" ON public.notifications
  FOR ALL USING (true);

-- LIVE LOCATIONS TABLE POLICIES
-- Allow users to manage their own live location
CREATE POLICY "live_locations_all_own" ON public.live_locations
  FOR ALL USING (auth.uid() = user_id);

-- Allow guardians to view live locations of users who added them
CREATE POLICY "live_locations_select_as_guardian" ON public.live_locations
  FOR SELECT USING (
    EXISTS (
      SELECT 1 FROM public.guardians
      JOIN public.users ON users.email = guardians.guardian_email
      WHERE guardians.user_id = live_locations.user_id
      AND users.id = auth.uid()
      AND guardians.status = 'active'
    )
  );

-- ============================================
-- STEP 5: CREATE FUNCTIONS
-- ============================================

-- Function to update updated_at timestamp
CREATE OR REPLACE FUNCTION public.handle_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Function to link guardian_user_id when guardian signs up
CREATE OR REPLACE FUNCTION public.link_guardian_user_id()
RETURNS TRIGGER AS $$
BEGIN
  UPDATE public.guardians
  SET guardian_user_id = NEW.id
  WHERE guardian_email = NEW.email AND guardian_user_id IS NULL;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Function to deactivate old FCM tokens when new one is added
CREATE OR REPLACE FUNCTION public.deactivate_old_fcm_tokens()
RETURNS TRIGGER AS $$
BEGIN
  -- Deactivate other tokens for the same device_id
  IF NEW.device_id IS NOT NULL THEN
    UPDATE public.fcm_tokens
    SET is_active = false
    WHERE user_id = NEW.user_id 
      AND device_id = NEW.device_id 
      AND id != NEW.id
      AND is_active = true;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Function to handle new user creation from auth.users
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS TRIGGER AS $$
BEGIN
  INSERT INTO public.users (id, email, name, phone)
  VALUES (
    NEW.id,
    NEW.email,
    COALESCE(NEW.raw_user_meta_data->>'name', NEW.email),
    COALESCE(NEW.raw_user_meta_data->>'phone', '')
  )
  ON CONFLICT (id) DO UPDATE SET
    email = EXCLUDED.email,
    name = COALESCE(EXCLUDED.name, public.users.name),
    phone = COALESCE(EXCLUDED.phone, public.users.phone);
  RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- ============================================
-- STEP 6: CREATE TRIGGERS
-- ============================================

-- Trigger to update updated_at on users table
DROP TRIGGER IF EXISTS set_users_updated_at ON public.users;
CREATE TRIGGER set_users_updated_at
  BEFORE UPDATE ON public.users
  FOR EACH ROW
  EXECUTE FUNCTION public.handle_updated_at();

-- Trigger to update updated_at on fcm_tokens table
DROP TRIGGER IF EXISTS set_fcm_tokens_updated_at ON public.fcm_tokens;
CREATE TRIGGER set_fcm_tokens_updated_at
  BEFORE UPDATE ON public.fcm_tokens
  FOR EACH ROW
  EXECUTE FUNCTION public.handle_updated_at();

-- Trigger to update updated_at on guardians table
DROP TRIGGER IF EXISTS set_guardians_updated_at ON public.guardians;
CREATE TRIGGER set_guardians_updated_at
  BEFORE UPDATE ON public.guardians
  FOR EACH ROW
  EXECUTE FUNCTION public.handle_updated_at();

-- Trigger to update updated_at on live_locations table
DROP TRIGGER IF EXISTS set_live_locations_updated_at ON public.live_locations;
CREATE TRIGGER set_live_locations_updated_at
  BEFORE UPDATE ON public.live_locations
  FOR EACH ROW
  EXECUTE FUNCTION public.handle_updated_at();

-- Trigger to link guardian_user_id when user signs up
DROP TRIGGER IF EXISTS link_guardian_on_user_insert ON public.users;
CREATE TRIGGER link_guardian_on_user_insert
  AFTER INSERT ON public.users
  FOR EACH ROW
  EXECUTE FUNCTION public.link_guardian_user_id();

-- Trigger to deactivate old FCM tokens
DROP TRIGGER IF EXISTS deactivate_old_tokens ON public.fcm_tokens;
CREATE TRIGGER deactivate_old_tokens
  AFTER INSERT ON public.fcm_tokens
  FOR EACH ROW
  EXECUTE FUNCTION public.deactivate_old_fcm_tokens();

-- Trigger to sync auth.users with public.users
DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
  AFTER INSERT ON auth.users
  FOR EACH ROW
  EXECUTE FUNCTION public.handle_new_user();

-- ============================================
-- STEP 7: GRANT PERMISSIONS
-- ============================================

-- Grant usage on schema
GRANT USAGE ON SCHEMA public TO anon, authenticated;

-- Grant permissions on all tables
GRANT ALL ON ALL TABLES IN SCHEMA public TO authenticated;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO anon;

-- Grant permissions on all sequences
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO authenticated;

-- Grant execute on all functions
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO authenticated, anon;

-- Set default privileges for future objects
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO authenticated;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO anon;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO authenticated;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO authenticated, anon;

-- ============================================
-- SETUP COMPLETE!
-- ============================================
-- Next steps:
-- 1. Go to https://app.supabase.com
-- 2. Select your project
-- 3. Go to SQL Editor
-- 4. Copy and paste this ENTIRE file
-- 5. Click "Run" to execute
-- 6. Verify tables in Table Editor
-- 7. Test your app!
-- ============================================
