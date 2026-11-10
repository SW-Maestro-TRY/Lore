-- Additive version support; existing v2/v3 progress and ordinals are unchanged.
ALTER TABLE zzal_pet DROP CONSTRAINT ck_zzal_pet_first_intro_version;
ALTER TABLE zzal_pet ADD CONSTRAINT ck_zzal_pet_first_intro_version CHECK (first_intro_version IN (2, 3, 4));
