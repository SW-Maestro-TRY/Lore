-- #548 「만들고 싶은 내용이 있어요」 — 작업의 길(mode)과 장면 확인에서 기다리는 상태.
-- mode: quick(이야기 없음, 지금 흐름) | own(적은 내용 그대로). 옛 줄은 전부 quick 이다.
ALTER TABLE webtoon_job ADD COLUMN IF NOT EXISTS mode VARCHAR(10) NOT NULL DEFAULT 'quick';
ALTER TABLE webtoon_job DROP CONSTRAINT IF EXISTS webtoon_job_status_check;
ALTER TABLE webtoon_job ADD CONSTRAINT webtoon_job_status_check CHECK (status IN
    ('QUEUED', 'RUNNING', 'AWAITING_SHEET', 'AWAITING_PICK', 'AWAITING_CAST', 'AWAITING_SCENES', 'DONE', 'ERROR'));
