-- haeun · 2026-10-04 11:33 KST
-- 목적: #638 관리자가 다른 사람의 작품을 비공개 · 삭제 · 경고 처리하고, 처리 기록을 남긴다.
--
-- webtoon_work 에는 「지금 관리자 처리 상태」만 둔다(작가 화면 · 공개 전환 막기가 이 값을 본다).
--   moderation              NULL(처리 없음) | HIDDEN(관리자 비공개) | REMOVED(관리자 휴지통)
--   moderation_reason       작가에게 보여 주는 사유
--   moderated_at            처리한 시각
--   moderation_was_public   처리 전에 공개였나 — 다시 공개 · 되살리기 때 원래 상태로 돌린다
-- 무엇을 언제 누가 했는지는 webtoon_moderation_log 에 한 줄씩 쌓는다(경고는 이 표에만 남는다).
--
-- 전부 NULL 기본값이라 옛 줄은 예전과 똑같이 보인다.
ALTER TABLE webtoon_work ADD COLUMN IF NOT EXISTS moderation VARCHAR(16);
ALTER TABLE webtoon_work ADD COLUMN IF NOT EXISTS moderation_reason VARCHAR(500);
ALTER TABLE webtoon_work ADD COLUMN IF NOT EXISTS moderated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE webtoon_work ADD COLUMN IF NOT EXISTS moderation_was_public BOOLEAN;

CREATE TABLE IF NOT EXISTS webtoon_moderation_log (
    id            BIGSERIAL PRIMARY KEY,
    run_id        VARCHAR(64)  NOT NULL,
    owner_user_id BIGINT,
    admin_user_id BIGINT       NOT NULL,
    action        VARCHAR(16)  NOT NULL,
    reason        VARCHAR(500) NOT NULL DEFAULT '',
    -- 작가에게 메일이 갔나. 게스트 작품 · 탈퇴 계정이면 false 로 남는다.
    notified      BOOLEAN      NOT NULL DEFAULT false,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_webtoon_moderation_action CHECK (action IN ('HIDE', 'UNHIDE', 'REMOVE', 'RESTORE', 'WARN'))
);

CREATE INDEX IF NOT EXISTS idx_webtoon_moderation_log_run ON webtoon_moderation_log (run_id);
CREATE INDEX IF NOT EXISTS idx_webtoon_moderation_log_owner ON webtoon_moderation_log (owner_user_id);
CREATE INDEX IF NOT EXISTS idx_webtoon_moderation_log_created ON webtoon_moderation_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_webtoon_work_moderation ON webtoon_work (moderation) WHERE moderation IS NOT NULL;
