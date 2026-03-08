-- Create live_locations table for on-demand location tracking
CREATE TABLE IF NOT EXISTS live_locations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    accuracy FLOAT,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Create index for faster queries
CREATE INDEX idx_live_locations_user_id ON live_locations(user_id);
CREATE INDEX idx_live_locations_updated_at ON live_locations(updated_at);

-- Enable Row Level Security
ALTER TABLE live_locations ENABLE ROW LEVEL SECURITY;

-- Policy: Users can insert/update their own location
CREATE POLICY "Users can manage their own location"
ON live_locations
FOR ALL
USING (auth.uid() = user_id)
WITH CHECK (auth.uid() = user_id);

-- Policy: Guardians can view locations of users who added them
CREATE POLICY "Guardians can view their users' locations"
ON live_locations
FOR SELECT
USING (
    EXISTS (
        SELECT 1 FROM guardians
        WHERE guardians.user_id = live_locations.user_id
        AND guardians.guardian_email = (
            SELECT email FROM auth.users WHERE id = auth.uid()
        )
    )
);

-- Function to automatically update updated_at timestamp
CREATE OR REPLACE FUNCTION update_live_location_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger to update timestamp on location update
CREATE TRIGGER update_live_location_timestamp
BEFORE UPDATE ON live_locations
FOR EACH ROW
EXECUTE FUNCTION update_live_location_timestamp();

-- Function to upsert live location (insert or update if exists)
CREATE OR REPLACE FUNCTION upsert_live_location(
    p_user_id UUID,
    p_latitude DOUBLE PRECISION,
    p_longitude DOUBLE PRECISION,
    p_accuracy FLOAT
)
RETURNS void AS $$
BEGIN
    INSERT INTO live_locations (user_id, latitude, longitude, accuracy)
    VALUES (p_user_id, p_latitude, p_longitude, p_accuracy)
    ON CONFLICT (user_id) 
    DO UPDATE SET
        latitude = EXCLUDED.latitude,
        longitude = EXCLUDED.longitude,
        accuracy = EXCLUDED.accuracy,
        updated_at = NOW();
END;
$$ LANGUAGE plpgsql;

-- Add unique constraint on user_id (one location per user)
ALTER TABLE live_locations ADD CONSTRAINT live_locations_user_id_unique UNIQUE (user_id);
