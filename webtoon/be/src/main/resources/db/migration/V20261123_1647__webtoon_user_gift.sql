-- 2026-11-23 16:47 KST
-- 목적: 특정 사용자에게 일시 오류 사과 안내 팝업과 보상 크레딧을 1회 지급하기 위한 범용 테이블.
--
-- 실제 대상 이메일·문구·크레딧은 코드나 마이그레이션에 하드코딩하지 않고 관리자 API
-- (POST /api/webtoon/v1/admin/gifts)로 등록한다.
-- 사용자가 로그인한 상태로 접속하면 미수령 행을 팝업으로 띄우고, 수령 시 CreditService.grantOnce
-- (refId = 'gift:' || ref_key)로 원장 중복 지급까지 막는다.
CREATE TABLE IF NOT EXISTS webtoon_user_gift (
    id          BIGSERIAL PRIMARY KEY,
    ref_key     VARCHAR(120) NOT NULL,
    -- lower(trim(email)). 대상 계정 판정용.
    email_key   VARCHAR(320) NOT NULL,
    title       VARCHAR(160) NOT NULL,
    message     TEXT         NOT NULL,
    credits     INTEGER      NOT NULL,
    created_by  BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL,
    claimed_by  BIGINT,
    claimed_at  TIMESTAMPTZ,
    CONSTRAINT uk_webtoon_user_gift_ref UNIQUE (ref_key)
);
CREATE INDEX IF NOT EXISTS idx_webtoon_user_gift_email_pending
    ON webtoon_user_gift (email_key, claimed_at);
