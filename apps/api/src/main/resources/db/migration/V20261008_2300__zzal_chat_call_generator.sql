-- 채팅 v1 — 대사를 누가 만들었나(#704).
--
-- ★ 칸 추가만 한다. 전부 nullable 이라 이미 있는 행(전부 템플릿)은 비어 있는 채로 둔다(비어 있음 = template).
-- ★ 통계·폴백 추적용이다 — 템플릿과 LLM 대사를 행 단위로 나란히 보고, 일 비용 상한을 이 표의 합으로 판정한다.
--   한 행에 대사가 둘(부름·답)이라 생성기 칸이 둘이고, 모델·비용·폴백 사유는 합쳐 적는다.

ALTER TABLE zzal_chat_call ADD COLUMN IF NOT EXISTS line_generator varchar(10);
ALTER TABLE zzal_chat_call ADD COLUMN IF NOT EXISTS reply_generator varchar(10);
ALTER TABLE zzal_chat_call ADD COLUMN IF NOT EXISTS model varchar(40);
ALTER TABLE zzal_chat_call ADD COLUMN IF NOT EXISTS cost_usd numeric(10,6);
ALTER TABLE zzal_chat_call ADD COLUMN IF NOT EXISTS filtered_reason varchar(64);
