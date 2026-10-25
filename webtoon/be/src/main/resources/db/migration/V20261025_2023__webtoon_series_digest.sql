-- 다음 화 버튼을 누를 때 앞 화를 읽고 정리한 연재 설정집(고정 설정·항목·진행 상태).
-- 앞 화 하나에 하나. 다음 화 창이 사람 대신 이 정리본으로 칸을 채운다.
CREATE TABLE webtoon_series_digest (
    source_job varchar(40) PRIMARY KEY,
    owner_id bigint NOT NULL,
    status varchar(20) NOT NULL,
    result_json text,
    error varchar(300),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
-- 다음 화에 넘길 설정집 항목. 사람이 뺀 항목은 여기서 빠진다. 'carry' 는 앞 화 설정집을 그대로 넘긴다는 뜻.
ALTER TABLE webtoon_series_draft ADD COLUMN entries_json text NOT NULL DEFAULT 'carry';
