-- New v4 first-night grace only; historical completed pets remain NULL and receive no retroactive grant.
ALTER TABLE zzal_pet ADD COLUMN first_night_chat_deadline_at timestamptz;
ALTER TABLE zzal_pet ADD COLUMN first_night_chat_ended_at timestamptz;
ALTER TABLE zzal_pet ADD CONSTRAINT ck_zzal_first_night_chat_grace
 CHECK(first_night_chat_ended_at IS NULL OR (first_night_chat_deadline_at IS NOT NULL AND first_night_chat_ended_at <= first_night_chat_deadline_at));
