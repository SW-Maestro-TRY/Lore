-- 채팅 실패 경로 지표·호칭 추출(#709).
--
-- ★ 추가만 한다(칸 더하기). 옛 칸·제약은 그대로.
-- zzal_chat_turn
--   outcome       펫 턴 — ok · retried_ok · failed_closed · truncated (재호출 1회 → 중립 닫는 말)
--   latency_ms    펫 턴 — 대사를 받는 데 걸린 시간(재호출까지 합)
--   call_me_code  사용자 턴 — 코드 패턴이 뽑은 호칭
--   call_me_model 사용자 턴 — 모델이 읽은 호칭(코드와 다르면 저장하지 않고 둘 다 남긴다)
--   user_said     사용자 턴 — 이번 질문 항목에 대한 답의 요지(모델, v2 기억 재료)
--   asked_back    사용자 턴 — 되물었나(코드 정규식 OR 모델)
-- zzal_chat_session
--   failed_closed LLM 이 두 번 다 실패해 중립 닫는 말로 닫힌 판인가(닫힘 사유는 CLOSED 그대로)

ALTER TABLE zzal_chat_turn ADD COLUMN IF NOT EXISTS outcome character varying(14);
ALTER TABLE zzal_chat_turn ADD COLUMN IF NOT EXISTS latency_ms integer;
ALTER TABLE zzal_chat_turn ADD COLUMN IF NOT EXISTS call_me_code character varying(20);
ALTER TABLE zzal_chat_turn ADD COLUMN IF NOT EXISTS call_me_model character varying(20);
ALTER TABLE zzal_chat_turn ADD COLUMN IF NOT EXISTS user_said character varying(160);
ALTER TABLE zzal_chat_turn ADD COLUMN IF NOT EXISTS asked_back boolean;

ALTER TABLE zzal_chat_session ADD COLUMN IF NOT EXISTS failed_closed boolean NOT NULL DEFAULT false;
