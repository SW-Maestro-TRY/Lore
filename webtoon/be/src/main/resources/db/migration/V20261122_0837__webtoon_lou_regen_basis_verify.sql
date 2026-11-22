-- haeun · 2026-11-22 08:37 KST
-- 목적: 구도 유지에 쓸 기준 장과 루의 자동 확인 요청을 다시 그리기 기록에 보관한다.

ALTER TABLE webtoon_page_regen ADD COLUMN basis INTEGER;
ALTER TABLE webtoon_page_regen ADD COLUMN verify BOOLEAN NOT NULL DEFAULT FALSE;
-- 여러 항목을 합친 계획의 메모를 마지막 지시까지 보존한다.
ALTER TABLE webtoon_page_regen ALTER COLUMN note TYPE TEXT;
