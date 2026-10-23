-- 사용자 설정집은 소유자 전용. 공개 여부/공유 권한 컬럼을 두지 않는다.
CREATE TABLE webtoon_private_lorebook (
 id varchar(40) PRIMARY KEY,
 owner_id bigint NOT NULL,
 created_at timestamptz NOT NULL,
 updated_at timestamptz NOT NULL,
 book_json text NOT NULL
);
CREATE INDEX idx_private_lorebook_owner_updated ON webtoon_private_lorebook(owner_id,updated_at DESC);
