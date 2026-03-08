-- Policy to allow authenticated users to insert their own alerts
CREATE POLICY "Enable insert for authenticated users on own alerts"
ON public.alert_history
FOR INSERT
TO authenticated
WITH CHECK (auth.uid() = user_id);

-- Policy to allow authenticated users to view their own alerts
CREATE POLICY "Enable read access for authenticated users on own alerts"
ON public.alert_history
FOR SELECT
TO authenticated
USING (auth.uid() = user_id);
