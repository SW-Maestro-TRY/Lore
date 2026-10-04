-- #548 조연 캐릭터 시트 — 인물 단계가 세운 다른 인물을 시트로 뽑은 기록(한 장에 크레딧 1).
-- 주인공 시트는 작품 폴더의 sheet.png 하나뿐이라 표가 없었다. 조연은 여러 명이고
-- 누가 어느 작품에서 뽑았는지를 남겨야 같은 이름을 두 번 받지 않고, 그림을 창고에 올린 키도 남는다.
CREATE TABLE IF NOT EXISTS webtoon_cast_sheet (
    id          BIGSERIAL PRIMARY KEY,
    job_id      VARCHAR(64)  NOT NULL,
    name        VARCHAR(80)  NOT NULL,
    s3_key      VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_webtoon_cast_sheet UNIQUE (job_id, name)
);
CREATE INDEX IF NOT EXISTS idx_webtoon_cast_sheet_job ON webtoon_cast_sheet (job_id);
