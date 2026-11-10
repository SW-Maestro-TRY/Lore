-- 관리자 "다시 만들기" 요청(#702) — 맥미니 러너가 집어 Codex 로 후보 격자를 만들어 올린다(선물 재생성의 LOCAL_REQUESTED 와 같은 뜻).
--
-- ★ 사용자 노출 상태(layer2_status·phase)와 따로 간다 — 요청만으로는 사용자 화면이 안 바뀐다.
--   러너가 후보를 올리면(또는 관리자가 직접 올리거나 고르면) 요청은 지워진다.
-- ★ 칸 추가만 한다(nullable). null = 요청 없음.
ALTER TABLE zzal_pet ADD COLUMN layer1_regen_requested_at timestamp(6) with time zone;
ALTER TABLE zzal_pet ADD COLUMN layer2_regen_requested_at timestamp(6) with time zone;
