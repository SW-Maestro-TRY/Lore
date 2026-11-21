-- haeun · 2026-11-22 00:30 KST
-- 목적: 편집실 「루」 대화 한 번(짚고 묻기 · 고친 뒤 확인)의 요청 · 결과와, 사람마다 하루 쓴 횟수(100번 상한)

CREATE TABLE webtoon_lou_turn (
    id varchar(36) PRIMARY KEY,
    run_id text NOT NULL,
    owner_id bigint NOT NULL,
    kind text NOT NULL,
    request_json text NOT NULL,
    status text NOT NULL,
    result_json text,
    error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_lou_recent ON webtoon_lou_turn(run_id, created_at DESC, id);
CREATE TABLE webtoon_lou_quota (
    owner_id bigint NOT NULL,
    day date NOT NULL,
    used integer NOT NULL,
    PRIMARY KEY(owner_id,day)
);
