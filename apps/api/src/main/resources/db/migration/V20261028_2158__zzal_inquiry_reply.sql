-- sanghoon416 · 2026-10-28 21:58 KST
-- 목적: 문의에 운영자 답장 한 번 + "더 물어보기" 사슬 + 사용자가 답장을 봤는지.
--   parent_id     더 물어보기로 이어 쓴 문의가 가리키는 앞 문의(같은 사용자). 첫 문의는 NULL.
--   reply_*       운영자 답장. 문의 한 건에 답장 한 번(두 번째는 409) — 그래서 별도 표 없이 칸으로 둔다.
--   reply_seen_at 사용자가 문의 화면에서 그 답장을 본 시각. 답장이 있고 이 값이 NULL 이면 메뉴에 점.
-- 기존 행은 전부 NULL(답장 없음)로 시작한다. 되채우기 없음.
ALTER TABLE zzal_inquiry
    ADD COLUMN parent_id bigint NULL REFERENCES zzal_inquiry(id),
    ADD COLUMN reply_text text NULL,
    ADD COLUMN replied_at timestamptz NULL,
    ADD COLUMN replied_by bigint NULL,
    ADD COLUMN reply_seen_at timestamptz NULL,
    ADD CONSTRAINT ck_zzal_inquiry_reply CHECK (
        (reply_text IS NULL AND replied_at IS NULL AND replied_by IS NULL AND reply_seen_at IS NULL)
        OR (reply_text IS NOT NULL AND replied_at IS NOT NULL AND char_length(reply_text) BETWEEN 1 AND 2000)
    );

CREATE INDEX idx_zzal_inquiry_user_created ON zzal_inquiry (user_id, created_at DESC);
CREATE INDEX idx_zzal_inquiry_unseen_reply ON zzal_inquiry (replied_at) WHERE reply_seen_at IS NULL;
