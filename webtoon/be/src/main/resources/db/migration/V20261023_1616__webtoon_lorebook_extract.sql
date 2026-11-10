CREATE TABLE webtoon_lore_extraction (
 id varchar(40) PRIMARY KEY, owner_id bigint NOT NULL, created_at timestamptz NOT NULL,
 status varchar(20) NOT NULL, result_json text, error varchar(300)
);
CREATE INDEX idx_lore_extraction_owner ON webtoon_lore_extraction(owner_id);
CREATE TABLE webtoon_lore_quota (
 owner_id bigint NOT NULL, day date NOT NULL, used integer NOT NULL CHECK (used >= 0),
 PRIMARY KEY(owner_id,day)
);
