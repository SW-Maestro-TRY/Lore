-- Existing progress uses v2 ordinals and must keep its original meaning. New first meetings use v3.
ALTER TABLE zzal_pet ADD COLUMN first_intro_version integer NOT NULL DEFAULT 2;
ALTER TABLE zzal_pet ADD CONSTRAINT ck_zzal_pet_first_intro_version CHECK (first_intro_version IN (2, 3));

