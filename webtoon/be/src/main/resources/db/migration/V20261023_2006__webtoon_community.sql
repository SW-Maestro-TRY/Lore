-- 작품 주변 대화와 공개 캐릭터 재사용. 개인 계정 삭제 시 FK로 정리한다.
ALTER TABLE webtoon_character ADD COLUMN IF NOT EXISTS community_public boolean NOT NULL DEFAULT false;
ALTER TABLE webtoon_character ADD COLUMN IF NOT EXISTS community_blocked boolean NOT NULL DEFAULT false;
ALTER TABLE webtoon_character ADD COLUMN IF NOT EXISTS reuse_agreed_at timestamptz;
CREATE TABLE webtoon_subscription (
 root_job varchar(64) NOT NULL, user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(root_job,user_id)
);
CREATE INDEX idx_subscription_user ON webtoon_subscription(user_id,created_at DESC);
CREATE TABLE webtoon_comment (
 id bigserial PRIMARY KEY, run_id varchar(64) NOT NULL,
 user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 body varchar(1000) NOT NULL CHECK(length(trim(body)) BETWEEN 1 AND 1000),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 deleted boolean NOT NULL DEFAULT false, hidden boolean NOT NULL DEFAULT false
);
CREATE INDEX idx_comment_run ON webtoon_comment(run_id,id DESC);
CREATE TABLE webtoon_community_notice (
 id bigserial PRIMARY KEY, user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 kind varchar(30) NOT NULL, event_key varchar(150) NOT NULL,
 run_id varchar(64) NOT NULL, character_id varchar(64), message varchar(300) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), read_at timestamptz,
 UNIQUE(user_id,event_key)
);
CREATE INDEX idx_community_notice_user ON webtoon_community_notice(user_id,id DESC);
CREATE TABLE webtoon_community_publication (job_id varchar(64) PRIMARY KEY, created_at timestamptz NOT NULL DEFAULT now());
-- 기존 완료 작품은 새 소식으로 재발송하지 않는다.
INSERT INTO webtoon_community_publication(job_id) SELECT public_id FROM webtoon_job WHERE status='DONE';
CREATE TABLE webtoon_character_use (
 job_id varchar(64) PRIMARY KEY, character_id varchar(64) NOT NULL,
 creator_id bigint REFERENCES users(id) ON DELETE SET NULL,
 character_name varchar(60) NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_character_use_character ON webtoon_character_use(character_id);
CREATE TABLE webtoon_community_report (
 id bigserial PRIMARY KEY, kind varchar(20) NOT NULL CHECK(kind IN ('comment','character')),
 target varchar(64) NOT NULL, user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 reason varchar(500) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), resolved_at timestamptz,
 UNIQUE(kind,target,user_id)
);
