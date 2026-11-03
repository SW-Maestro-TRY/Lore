-- 세계관 체험으로 만든 작업에 어느 세계 · 사건이었는지 남긴다(2026-11-03).
-- 「내가 체험한 만화」(GET /play/runs/mine)와 「이 사건으로 만든 만화」(GET /play/events/{id}/runs)가 이것으로 찾는다.
-- 일반 만들기 작업은 비어 있다.
ALTER TABLE webtoon_job ADD COLUMN IF NOT EXISTS play_world varchar(40);
ALTER TABLE webtoon_job ADD COLUMN IF NOT EXISTS play_event varchar(80);
CREATE INDEX IF NOT EXISTS idx_webtoon_job_play_event ON webtoon_job(play_event, created_at DESC);
