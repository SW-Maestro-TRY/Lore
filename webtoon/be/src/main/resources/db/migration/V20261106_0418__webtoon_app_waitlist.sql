-- haeun · 2026-11-06 04:18 KST
-- 목적: #981 LORE 앱 출시 알림 신청 — 둘러보기 맨 위 「LORE 앱 설치하기」 띠에서 받은 이메일.
--
-- 앱은 아직 없다. 띠를 누르면 「아직 앱 출시는 준비중이에요! 이메일을 알려주시면 출시날
-- 50크레딧을 드릴게요!」 창이 뜨고, 여기에 이메일이 쌓인다. 출시날 이 목록으로 메일을 보내고
-- 50크레딧을 준다 — 준 줄은 granted_at 을 채워 두 번 주지 않는다.
--
-- ── 한 줄이 한 이메일이다 ───────────────────────────────────────────
-- 같은 이메일로 다시 신청하면 새 줄을 만들지 않고 처음 줄을 둔다(대소문자 무시, email_key).
-- 로그인한 채로 신청하면 user_id 를 적는다. 출시날 크레딧은 이 계정으로, 없으면 같은 이메일로
-- 가입한 계정으로 준다.
--
-- user_id 에 외래키를 안 거는 이유: 탈퇴 정리(WebtoonPurge)가 이 표를 직접 지운다
-- (webtoon_push_subscription 과 같은 이유).
CREATE TABLE IF NOT EXISTS webtoon_app_waitlist (
    id           BIGSERIAL PRIMARY KEY,
    email        VARCHAR(320) NOT NULL,
    -- 겹침 판정용. lower(trim(email)).
    email_key    VARCHAR(320) NOT NULL,
    user_id      BIGINT,
    browser_uid  VARCHAR(64),
    -- 신청할 때 고른 기기 탭(ios · android). 출시 안내 메일을 어느 스토어로 보낼지 정할 때 쓴다.
    device       VARCHAR(16),
    -- 어디서 신청했나(works_banner 등).
    source       VARCHAR(40),
    lang         VARCHAR(8)   NOT NULL DEFAULT 'ko',
    created_at   TIMESTAMPTZ  NOT NULL,
    -- 출시날 50크레딧을 준 때. 비어 있으면 아직 안 줬다.
    granted_at   TIMESTAMPTZ,
    CONSTRAINT uk_webtoon_app_waitlist_email UNIQUE (email_key)
);
CREATE INDEX IF NOT EXISTS idx_webtoon_app_waitlist_user ON webtoon_app_waitlist (user_id);
