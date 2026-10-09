-- 튜토리얼 부름(BABY) 첫 턴 실패 경로(#709) — 판을 닫지 않고 재시도 대기 → 중립 한 줄.
--
-- ★ 추가만 한다(칸 더하기). 옛 칸·제약은 그대로.
-- zzal_chat_session
--   retry_after          BABY 첫 턴 생성이 실패해 다시 부를 수 있는 시각(이 전에는 모델 호출 0). 첫 턴이 붙으면 비운다.
--                        값이 있는 동안 판은 "열려 있지 않다"(답할 수 없고 화면에 안 보인다).
--   first_line_failures  첫 턴 생성에서 실패한 모델 호출 수의 누적(3이면 중립 한 줄로 연다). 지표용.

ALTER TABLE zzal_chat_session ADD COLUMN IF NOT EXISTS retry_after timestamp(6) with time zone;
ALTER TABLE zzal_chat_session ADD COLUMN IF NOT EXISTS first_line_failures integer NOT NULL DEFAULT 0;
