-- #534 현대 로맨스에서 이야기 전에 상대 인물을 고르는 동안의 상태.
ALTER TABLE webtoon_job DROP CONSTRAINT IF EXISTS webtoon_job_status_check;
ALTER TABLE webtoon_job ADD CONSTRAINT webtoon_job_status_check CHECK (status IN
    ('QUEUED', 'RUNNING', 'AWAITING_SHEET', 'AWAITING_PICK', 'AWAITING_CAST', 'DONE', 'ERROR'));
