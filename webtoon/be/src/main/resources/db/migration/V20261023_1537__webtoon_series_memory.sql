CREATE TABLE webtoon_series_draft (
    source_job varchar(40) PRIMARY KEY,
    owner_id bigint NOT NULL,
    fixed_settings text NOT NULL,
    progress_state text NOT NULL,
    next_story text NOT NULL,
    revision bigint NOT NULL DEFAULT 0
);
CREATE TABLE webtoon_series_chapter (
    job_id varchar(40) PRIMARY KEY,
    owner_id bigint NOT NULL,
    root_job varchar(40) NOT NULL,
    source_job varchar(40) NOT NULL,
    episode_number integer NOT NULL CHECK (episode_number > 1),
    request_token varchar(64) NOT NULL,
    memory_json text NOT NULL,
    CONSTRAINT uq_series_request UNIQUE (root_job,request_token)
);
CREATE INDEX idx_series_root ON webtoon_series_chapter(root_job);
