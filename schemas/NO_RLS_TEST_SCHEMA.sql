-- ============================================
-- VASATEYSEC - NO RLS TEST SCHEMA
-- ============================================
-- This schema has NO security restrictions for testing
-- WARNING: Use ONLY for testing! Not for production!
-- ============================================

-- ============================================
-- STEP 1: DROP EXISTING TABLES (Clean Start)
-- ============================================

DROP TABLE IF EXISTS public.notifications CASCADE;
DROP TABLE IF EXISTS public.alert_recipients CASCADE;
DROP TABLE IF EXISTS public.alert_history CASCADE;
DROP TABLE IF EXISTS public.guardians CASCADE;
DROP TABLE IF EXISTS public.fcm_tokens CASCADE;
DROP TABLE IF EXISTS public.users CASCADE;

-- ============================================
-- STEP 2: CREATE TABLES (NO RLS)
-- ============================================

-- 1. USERS TABLE
CREATE TABLE public.users (
  id UUID PRIMARY KEY,
  name TEXT NOT NULL,
  email TEXT NOT NULL UNIQUE,
  phone TEXT,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 2. FCM TOKENS TABLE
CREATE TABLE public.fcm_tokens (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
  token TEXT NOT NULL UNIQUE,
  device_id TEXT,
  device_name TEXT,
  platform TEXT DEFAULT 'android',
  is_active BOOLEAN DEFAULT true,
  last_used_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 3. GUARDIANS TABLE
CREATE TABLE public.guardians (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
  guardian_email TEXT NOT NULL,
  guardian_user_id UUID REFERENCES public.users(id) ON DELETE SET NULL,
  status TEXT DEFAULT 'active',
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  UNIQUE(user_id, guardian_email)
);

-- 4. ALERT HISTORY TABLE
CREATE TABLE public.alert_history (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
  user_name TEXT NOT NULL,
  user_email TEXT NOT NULL,
  user_phone TEXT NOT NULL,
  latitude DOUBLE PRECISION,
  longitude DOUBLE PRECISION,
  location_accuracy REAL,
  alert_type TEXT DEFAULT 'voice_help',
  status TEXT DEFAULT 'sent',
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 5. ALERT RECIPIENTS TABLE
CREATE TABLE public.alert_recipients (
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

-- 6. NOTIFICATIONS TABLE
CREATE TABLE public.notifications (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  alert_id UUID REFERENCES public.alert_history(id) ON DELETE CASCADE,
  token TEXT NOT NULL,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  recipient_email TEXT NOT NULL,
  is_self_alert BOOLEAN DEFAULT false,
  status TEXT DEFAULT 'pending',
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  sent_at TIMESTAMP WITH TIME ZONE
);

-- ============================================
-- STEP 3: CREATE INDEXES
-- ============================================

CREATE INDEX idx_users_email ON public.users(email);
CREATE INDEX idx_fcm_tokens_user_id ON public.fcm_tokens(user_id);
CREATE INDEX idx_fcm_tokens_token ON public.fcm_tokens(token);
CREATE INDEX idx_guardians_user_id ON public.guardians(user_id);
CREATE INDEX idx_guardians_email ON public.guardians(guardian_email);
CREATE INDEX idx_alert_history_user_id ON public.alert_history(user_id);
CREATE INDEX idx_alert_recipients_alert_id ON public.alert_recipients(alert_id);

-- ============================================
-- STEP 4: DISABLE RLS (NO SECURITY)
-- ============================================

ALTER TABLE public.users DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.fcm_tokens DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.guardians DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.alert_history DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.alert_recipients DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.notifications DISABLE ROW LEVEL SECURITY;

-- ============================================
-- STEP 5: GRANT ALL PERMISSIONS
-- ============================================

-- Grant all permissions to everyone (for testing only!)
GRANT ALL ON public.users TO anon, authenticated;
GRANT ALL ON public.fcm_tokens TO anon, authenticated;
GRANT ALL ON public.guardians TO anon, authenticated;
GRANT ALL ON public.alert_history TO anon, authenticated;
GRANT ALL ON public.alert_recipients TO anon, authenticated;
GRANT ALL ON public.notifications TO anon, authenticated;

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO anon, authenticated;

-- ============================================
-- STEP 6: CREATE HELPER FUNCTIONS
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
-- STEP 7: CREATE TRIGGERS
-- ============================================

DROP TRIGGER IF EXISTS set_users_updated_at ON public.users;
CREATE TRIGGER set_users_updated_at
  BEFORE UPDATE ON public.users
  FOR EACH ROW
  EXECUTE FUNCTION public.handle_updated_at();

DROP TRIGGER IF EXISTS set_fcm_tokens_updated_at ON public.fcm_tokens;
CREATE TRIGGER set_fcm_tokens_updated_at
  BEFORE UPDATE ON public.fcm_tokens
  FOR EACH ROW
  EXECUTE FUNCTION public.handle_updated_at();

DROP TRIGGER IF EXISTS set_guardians_updated_at ON public.guardians;
CREATE TRIGGER set_guardians_updated_at
  BEFORE UPDATE ON public.guardians
  FOR EACH ROW
  EXECUTE FUNCTION public.handle_updated_at();

DROP TRIGGER IF EXISTS link_guardian_on_user_insert ON public.users;
CREATE TRIGGER link_guardian_on_user_insert
  AFTER INSERT ON public.users
  FOR EACH ROW
  EXECUTE FUNCTION public.link_guardian_user_id();

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
  AFTER INSERT ON auth.users
  FOR EACH ROW
  EXECUTE FUNCTION public.handle_new_user();

-- ============================================
-- SETUP COMPLETE!
-- ============================================
-- WARNING: This schema has NO security!
-- All users can access ALL data!
-- Use ONLY for testing to isolate the issue!
-- ============================================

SELECT 'NO RLS TEST SCHEMA INSTALLED SUCCESSFULLY!' as status;
SELECT 'All tables created with FULL permissions' as info;
SELECT 'RLS is DISABLED on all tables' as security_warning;
