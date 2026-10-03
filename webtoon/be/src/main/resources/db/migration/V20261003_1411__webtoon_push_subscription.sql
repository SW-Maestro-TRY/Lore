-- haeun · 2026-10-03 14:11 KST
-- 목적: #599 웹푸시 — 이 기기(브라우저)로 알림을 받겠다고 한 기록.
--
-- 메일은 완성·실패 때만 간다. 그런데 「2번 확인하며」는 중간에 사람이 골라야
-- 다음으로 넘어가서(상대 인물 · 이야기 · 장면 · 시트), 화면을 닫은 사람은 작업이
-- 자기를 기다리고 있는 줄 모른다. 푸시는 그 순간마다 간다.
--
-- ── 한 줄이 한 기기다 ──────────────────────────────────────────────
-- endpoint 는 브라우저가 준 푸시 서버 주소이고 기기마다 다르다. 같은 기기가
-- 다시 구독하면(로그인한 뒤 등) 같은 줄을 고쳐 쓴다.
--
-- ── 누구의 작업을 받나 ─────────────────────────────────────────────
-- 로그인한 사람의 작업은 user_id 로, 게스트의 작업은 browser_uid(webtoon_job 과
-- 같은 값)로 찾는다. 로그인한 사람이 구독하면 둘 다 적힌다 — 로그인 전에 이
-- 브라우저에서 만들던 작업도 알림이 가야 한다.
--
-- user_id 에 외래키를 안 거는 이유: 탈퇴 정리(WebtoonPurge)가 이 표를 직접 지운다.
-- 외래키를 걸면 지우는 순서를 하나 더 지켜야 할 뿐 얻는 것이 없다.
CREATE TABLE IF NOT EXISTS webtoon_push_subscription (
    id            BIGSERIAL PRIMARY KEY,
    endpoint      VARCHAR(1024) NOT NULL,
    p256dh        VARCHAR(128)  NOT NULL,
    auth          VARCHAR(64)   NOT NULL,
    user_id       BIGINT,
    browser_uid   VARCHAR(64),
    -- 알림 문구의 언어. 구독할 때 화면 언어를 적는다.
    lang          VARCHAR(8)    NOT NULL DEFAULT 'ko',
    created_at    TIMESTAMPTZ   NOT NULL,
    -- 마지막으로 구독을 확인했거나 보낸 때. 오래 안 쓰인 줄은 보관기간 청소가 지운다.
    used_at       TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uk_webtoon_push_endpoint UNIQUE (endpoint)
);
CREATE INDEX IF NOT EXISTS idx_webtoon_push_user ON webtoon_push_subscription (user_id);
CREATE INDEX IF NOT EXISTS idx_webtoon_push_uid ON webtoon_push_subscription (browser_uid);
