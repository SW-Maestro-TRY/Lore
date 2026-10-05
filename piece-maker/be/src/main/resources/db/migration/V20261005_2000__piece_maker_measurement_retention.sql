-- 개인별 최초 시각을 무기한 남기지 않는다. 삭제 공백도 최대 365일 정책 안에서만 보관한다.
CREATE TABLE piece_maker_measurement_gap (
    user_id bigint PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_piece_maker_measurement_gap_time ON piece_maker_measurement_gap(recorded_at);
CREATE INDEX idx_piece_maker_first_result_view_time ON piece_maker_first_result_view(viewed_at);

-- 아직 보관 범위 안인 제출·유입·열람을 선택 삭제하면 신규 여부를 확정할 근거가 사라진다.
-- 삭제 행을 복제하지 않고 계정의 확인불가 상태만 남긴다. 기간 만료·탈퇴 계정은 제외한다.
CREATE FUNCTION piece_maker_note_measurement_gap() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    affected_user bigint;
    original_at timestamptz;
BEGIN
    IF TG_TABLE_NAME = 'hypotheses' THEN
        affected_user := OLD.user_id;
        original_at := OLD.created_at;
    ELSIF TG_TABLE_NAME = 'piece_maker_ad_landing' THEN
        affected_user := OLD.owner_user_id;
        original_at := OLD.landed_at;
    ELSE
        affected_user := OLD.user_id;
        original_at := OLD.viewed_at;
    END IF;
    IF affected_user IS NOT NULL AND original_at > clock_timestamp() - interval '364 days' THEN
        INSERT INTO piece_maker_measurement_gap (user_id, recorded_at)
        SELECT id, clock_timestamp() FROM users
        WHERE id = affected_user AND status = 'ACTIVE' AND deleted_at IS NULL
        ON CONFLICT (user_id) DO UPDATE SET recorded_at = EXCLUDED.recorded_at;
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER piece_maker_submission_deleted AFTER DELETE ON hypotheses
    FOR EACH ROW EXECUTE FUNCTION piece_maker_note_measurement_gap();
CREATE TRIGGER piece_maker_landing_deleted AFTER DELETE ON piece_maker_ad_landing
    FOR EACH ROW EXECUTE FUNCTION piece_maker_note_measurement_gap();
CREATE TRIGGER piece_maker_view_deleted AFTER DELETE ON piece_maker_first_result_view
    FOR EACH ROW EXECUTE FUNCTION piece_maker_note_measurement_gap();
