-- 펫의 시간대(IANA 이름, 예 'America/Los_Angeles').
-- NULL = 아직 관측 못 함. 읽는 쪽은 전부 Asia/Seoul 로 본다(지금까지와 같은 동작).
-- 값은 펫 생성 때, 또는 NULL 인 펫의 첫 요청(X-Zzal-Tz 헤더)에서 딱 한 번 채워지고 다시 바뀌지 않는다.
-- 기존 행은 채우지 않는다(과거 날짜 칸은 그때 KST 규칙으로 맞게 적힌 값이다).
ALTER TABLE zzal_pet ADD COLUMN time_zone varchar(64);
