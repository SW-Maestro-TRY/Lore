-- Null deliberately preserves existing nine-step progress and existing running clocks.
ALTER TABLE zzal_pet ADD COLUMN first_intro_step integer;
ALTER TABLE zzal_pet ADD CONSTRAINT ck_zzal_pet_first_intro_step CHECK (first_intro_step BETWEEN 0 AND 14);
