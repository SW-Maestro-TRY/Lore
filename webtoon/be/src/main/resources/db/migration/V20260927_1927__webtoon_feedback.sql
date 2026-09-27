-- haeun · 2026-09-27 19:27 KST
-- 목적: 사용자 검증 설문과 피드백을 받는다(#471, webtoon/docs/validation.md).
--       완성 직후의 짧은 설문(SHORT)과 마이페이지 「피드백 보내기」의 전체 설문(FULL)을
--       한 표에 둔다. 행동 기록(webtoon_event)과 따로 두는 이유: 전체 설문에는 사람이
--       쓴 글(자유 의견·연락처)이 들어가는데, 행동 기록은 글을 싣지 않기로 약속했다.
--       작품 번호(run_id)로 행동 기록 · 「넣은 설정이 간 곳」과 잇는다.
CREATE TABLE webtoon_feedback (
    id               BIGSERIAL PRIMARY KEY,
    kind             VARCHAR(10)  NOT NULL,           -- SHORT · FULL
    run_id           VARCHAR(64),                     -- 어느 작품에 대한 답인가 (FULL 은 없을 수 있다)
    user_id          BIGINT,                          -- 로그인했으면 계정
    uid              VARCHAR(64),                     -- 브라우저 번호(lore_uid)
    answers          TEXT         NOT NULL,           -- {"S1":4,"S3":"partly"} — 서버가 정한 질문·값만
    comment          TEXT,                            -- 자유 의견 (FULL 만)
    wants_interview  BOOLEAN      NOT NULL DEFAULT FALSE,
    contact          VARCHAR(200),                    -- 인터뷰 연락처 (FULL 만, 원할 때만)
    rewarded         INTEGER      NOT NULL DEFAULT 0, -- 이 답으로 준 크레딧
    created_at       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_webtoon_feedback_run ON webtoon_feedback (run_id);
CREATE INDEX idx_webtoon_feedback_user ON webtoon_feedback (user_id);
CREATE INDEX idx_webtoon_feedback_kind_time ON webtoon_feedback (kind, created_at);
