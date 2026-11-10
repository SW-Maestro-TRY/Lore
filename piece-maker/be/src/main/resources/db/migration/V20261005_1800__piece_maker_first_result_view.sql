-- 계정별 첫 정상 판정 열람. 기존 열람 이력은 추정해 채우지 않는다.
-- 일반 행동 기록의 보관 기한과 별도로 유지해 다음 방문을 새 첫 열람으로 세지 않는다.
CREATE TABLE piece_maker_first_result_view (
    user_id       bigint PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    hypothesis_id bigint REFERENCES hypotheses (id) ON DELETE SET NULL,
    viewed_at     timestamptz NOT NULL
);

-- 가설 삭제는 계정의 첫 열람 사실을 없애지 않는다. 계정을 지우면 함께 지운다.
CREATE INDEX idx_piece_maker_first_result_view_hypothesis ON piece_maker_first_result_view (hypothesis_id);
