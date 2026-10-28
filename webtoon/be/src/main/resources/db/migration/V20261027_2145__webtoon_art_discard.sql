-- haeun · 2026-10-27 21:45 KST
-- 목적: 더 안 쓰는 그림을 S3 에서 지우기 전까지 적어 두는 대기열.
-- 다시 그리기·다시 굽기는 새 이름으로 올리고 DB 에는 새 이름만 적어서, 예전 그림을 가리키는 곳이
-- 어디에도 남지 않았다. 그러면 작품을 지우거나 탈퇴해도 예전 판은 영원히 남는다.
-- 이름이 바뀌는 순간 여기에 적고, 7일 뒤 ArtDiscardSweep 이 아직 아무 데서도 안 쓰는지 다시 보고 지운다.
CREATE TABLE IF NOT EXISTS webtoon_art_discard (
    s3_key        VARCHAR(300) PRIMARY KEY,
    reason        VARCHAR(20)  NOT NULL,
    discarded_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_webtoon_art_discard_at ON webtoon_art_discard (discarded_at);
