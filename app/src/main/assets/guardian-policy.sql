CREATE POLICY "Enable read access for all authenticated users on guardians"
ON public.guardians
FOR SELECT
TO authenticated
USING (true);
