-- When the owner last opened the attendance gifts screen. Gifts granted after it are "new" (red dot).
-- NULL means never opened: every granted gift (except the initial legacy background) counts as new. No backfill.
ALTER TABLE zzal_pet ADD COLUMN rewards_seen_at timestamptz;
