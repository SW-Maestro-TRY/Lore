-- null은 이미 사용 중인 기존 캐릭터: 첫만남을 다시 강제하지 않는다.
ALTER TABLE zzal_pet ADD COLUMN IF NOT EXISTS first_meeting_state varchar(24);
ALTER TABLE zzal_pet ADD COLUMN IF NOT EXISTS call_me_source varchar(24);
