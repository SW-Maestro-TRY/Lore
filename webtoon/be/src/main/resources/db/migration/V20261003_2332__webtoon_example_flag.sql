-- haeun · 2026-10-03
-- 목적: #614 예시 작품을 「누가 올렸나」(browser_uid = 'lore-example-seed')로만 알아보던 것을 명시적인 값으로.
--
-- 예시는 저장소 폴더에서 서버가 뜰 때 심었고, 심은 줄이 예시인지를 browser_uid 의 특별한 값으로만
-- 알 수 있었다. 관리자 API 로 올리거나, 같은 환경에서 만든 작품을 예시로 지정하려면 값이 따로 있어야
-- 한다. 순서(example_order)는 관리자 화면에서 바꾼다.
--
-- 기본값 false / NULL 이라 옛 줄은 예전과 똑같이 보인다. 이미 심겨 있는 예시는 아래에서 한 번에 켠다.
ALTER TABLE webtoon_work ADD COLUMN IF NOT EXISTS is_example BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE webtoon_work ADD COLUMN IF NOT EXISTS example_order INTEGER;

UPDATE webtoon_work SET is_example = true WHERE browser_uid = 'lore-example-seed' AND NOT is_example;

CREATE INDEX IF NOT EXISTS idx_webtoon_work_example ON webtoon_work (example_order, id) WHERE is_example;
