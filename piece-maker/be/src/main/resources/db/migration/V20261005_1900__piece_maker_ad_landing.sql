-- 광고 URL의 서버 최초 수신과 이후 로그인 귀속. 기존 익명 이벤트를 소급 연결하지 않는다.
CREATE TABLE piece_maker_ad_landing (
    id uuid PRIMARY KEY,
    anon_id varchar(32) NOT NULL,
    request_key uuid NOT NULL,
    owner_user_id bigint REFERENCES users(id) ON DELETE CASCADE,
    landed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    client_landed_at timestamptz NOT NULL,
    utm_source varchar(64) NOT NULL,
    utm_medium varchar(64) NOT NULL,
    utm_campaign varchar(64) NOT NULL,
    utm_content varchar(64) NOT NULL,
    placement varchar(64) NOT NULL,
    claimed_at timestamptz,
    ambiguous boolean NOT NULL DEFAULT false,
    ambiguous_at timestamptz,
    CONSTRAINT uk_piece_maker_ad_landing_request UNIQUE (anon_id, request_key),
    CONSTRAINT ck_piece_maker_ad_landing_owner CHECK ((owner_user_id IS NULL) = (claimed_at IS NULL)),
    CONSTRAINT ck_piece_maker_ad_landing_ambiguous CHECK (ambiguous = (ambiguous_at IS NOT NULL))
);
CREATE INDEX idx_piece_maker_ad_landing_owner ON piece_maker_ad_landing (owner_user_id, landed_at, id);
CREATE INDEX idx_piece_maker_ad_landing_time ON piece_maker_ad_landing (landed_at);
