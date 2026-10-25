-- 시간대 2단계 — 밤 스위프를 시간대마다, 펫마다 한 번.
--
-- 1) zzal_night_run: 키를 (night_of) → (night_of, time_zone). 옛 행은 전부 한국 밤이었다.
ALTER TABLE zzal_night_run ADD COLUMN time_zone varchar(64) NOT NULL DEFAULT 'Asia/Seoul';
ALTER TABLE zzal_night_run DROP CONSTRAINT zzal_night_run_pkey;
ALTER TABLE zzal_night_run ADD CONSTRAINT zzal_night_run_pkey PRIMARY KEY (night_of, time_zone);

-- 2) zzal_pet.last_swept_night_of: 펫 단위 멱등. 스위프는 이 값이 그 밤보다 앞(또는 NULL)일 때만 그 펫을 돈다.
ALTER TABLE zzal_pet ADD COLUMN last_swept_night_of date;

-- 지금까지 돈 마지막 한국 밤으로 채운다. 그래야 배포 직후 시간대를 처음 관측한 펫(예: 미 서부)이
-- 이미 돈 한국 밤과 같은 날짜의 현지 밤을 한 번 더 돌지 않는다. 판이 한 번도 없었으면 NULL 그대로.
UPDATE zzal_pet
   SET last_swept_night_of = (SELECT max(night_of) FROM zzal_night_run WHERE finished_at IS NOT NULL)
 WHERE phase = 'ALIVE';
