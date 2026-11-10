-- sanghoon416 · 2026-10-22 23:47 KST
-- 목적:

-- Additive: legacy rows retain NULL envelope values. No raw events expire or are deleted.
ALTER TABLE zzal_event ADD COLUMN IF NOT EXISTS event_id varchar(36);
ALTER TABLE zzal_event ADD COLUMN IF NOT EXISTS schema_version integer;
ALTER TABLE zzal_event ADD COLUMN IF NOT EXISTS session_id varchar(36);
ALTER TABLE zzal_event ADD COLUMN IF NOT EXISTS context varchar(16);
ALTER TABLE zzal_event ADD COLUMN IF NOT EXISTS source_screen varchar(64);
ALTER TABLE zzal_event ADD COLUMN IF NOT EXISTS event_origin varchar(16) NOT NULL DEFAULT 'browser';
CREATE UNIQUE INDEX IF NOT EXISTS uk_event_origin_event_id ON zzal_event (event_origin, event_id);
ALTER TABLE zzal_event ADD COLUMN IF NOT EXISTS reported_ts bigint;
ALTER TABLE zzal_event ADD COLUMN IF NOT EXISTS timestamp_quality varchar(24);
ALTER TABLE zzal_event ADD COLUMN IF NOT EXISTS identity_status varchar(24);
