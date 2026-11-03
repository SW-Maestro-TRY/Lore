-- 사용자가 지어 올리는 세계관 체험 세계(2026-11-03).
-- 운영자가 정한 세계는 지금처럼 jar 안의 play/worlds.json 에 있고, 여기는 사용자가 「세계관 만들기」로 지은 세계다.
-- status: DRAFT(만든 사람만 본다) · PUBLISHED(세계관 목록에 올라간다). making: IDLE · RUNNING · ERROR.
-- books_json 은 만들 때 참고한 내 설정집의 사본이다 — 설정집을 나중에 고쳐도 올린 세계는 바뀌지 않는다.
-- 화면에는 내주지 않는다(설정집은 소유자 전용). 이 세계로 만드는 웹툰의 설정집으로만 쓴다.
CREATE TABLE IF NOT EXISTS webtoon_play_world (
 id varchar(40) PRIMARY KEY,
 owner_id bigint NOT NULL,
 status varchar(20) NOT NULL,
 making varchar(20) NOT NULL,
 error varchar(300),
 doc_json text NOT NULL,
 input_json text NOT NULL,
 books_json text NOT NULL,
 created_at timestamptz NOT NULL,
 updated_at timestamptz NOT NULL,
 made_at timestamptz,
 published_at timestamptz
);
CREATE INDEX IF NOT EXISTS idx_play_world_owner_updated ON webtoon_play_world(owner_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_play_world_status ON webtoon_play_world(status, published_at DESC);

-- 세계 만들기는 글 한 번 + 예시 주인공 그림 둘이 나간다. 하루 횟수를 센다(설정 정리 webtoon_lore_quota 와 같은 방식).
CREATE TABLE IF NOT EXISTS webtoon_play_world_quota (
 owner_id bigint NOT NULL,
 day date NOT NULL,
 used int NOT NULL,
 PRIMARY KEY (owner_id, day)
);

-- 세계의 예시 주인공. 만든 사람의 캐릭터 목록·무료 횟수에는 안 섞이고(play_world 가 있으면 뺀다),
-- 세계가 올라가 있는 동안(play_public)은 누구나 그 세계에 넣어 볼 수 있다.
ALTER TABLE webtoon_character ADD COLUMN IF NOT EXISTS play_world varchar(40);
ALTER TABLE webtoon_character ADD COLUMN IF NOT EXISTS play_public boolean NOT NULL DEFAULT false;
CREATE INDEX IF NOT EXISTS idx_webtoon_character_play_world ON webtoon_character(play_world);
