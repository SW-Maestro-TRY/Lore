-- Preserve legacy ownership at the retirement cutover. Existing assets and motion rows stay intact.
-- Flyway executes this once, before the new frozen-ownership reader is enabled.
LOCK TABLE zzal_pet IN SHARE ROW EXCLUSIVE MODE;
ALTER TABLE zzal_pet ADD COLUMN legacy_layer2_owned_mask integer;
UPDATE zzal_pet p SET legacy_layer2_owned_mask =
    CASE WHEN layer2_status = 'READY' THEN
      (CASE WHEN feeds >= 9 THEN 1 ELSE 0 END) +
      (CASE WHEN snacks >= 9 THEN 2 ELSE 0 END) +
      (CASE WHEN cleans >= 13 THEN 4 ELSE 0 END) +
      (CASE WHEN bath_count >= 3 THEN 8 ELSE 0 END) +
      (CASE WHEN chat_answers >= 4 THEN 16 ELSE 0 END) +
      (CASE WHEN pets >= 4 THEN 32 ELSE 0 END) +
      (CASE WHEN game_starts >= 4 THEN 64 ELSE 0 END) +
      (CASE WHEN wakes >= 4 THEN 128 ELSE 0 END)
    ELSE 0 END |
    COALESCE((SELECT sum(DISTINCT (1 << (m.seq - 9)))::integer
              FROM zzal_motion m WHERE m.pet_id=p.id AND m.seq BETWEEN 9 AND 16
                AND m.unlocked_at IS NOT NULL), 0)
WHERE p.legacy_layer2_owned_mask IS NULL;
ALTER TABLE zzal_pet ALTER COLUMN legacy_layer2_owned_mask SET DEFAULT 0;
ALTER TABLE zzal_pet ALTER COLUMN legacy_layer2_owned_mask SET NOT NULL;
ALTER TABLE zzal_pet ADD CONSTRAINT ck_legacy_layer2_owned_mask CHECK (legacy_layer2_owned_mask BETWEEN 0 AND 255);
