-- 새 온보딩 원문은 기존 personality/note 투영과 독립 보존한다.
-- null인 기존 행은 기존 채팅 입력으로 계속 동작한다.
ALTER TABLE zzal_pet ADD COLUMN IF NOT EXISTS authored_persona text;
