-- Additive selection provenance; no existing ownership/progress/default background is rewritten.
ALTER TABLE zzal_attendance_reward_slot ADD COLUMN source_cluster_key text;
