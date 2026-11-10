-- Per-pet last successful explicit care situation. Historical rows stay null.
ALTER TABLE zzal_pet ADD COLUMN care_motion_cursor jsonb;
