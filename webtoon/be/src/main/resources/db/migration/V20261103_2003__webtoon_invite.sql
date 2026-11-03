-- haeun · 2026-11-03 20:03 KST
-- 목적: 친구 초대 보상.
-- 초대한 사람은 친구가 가입하면 10C, 그 친구가 웹툰을 처음 완성하면 10C 를 더 받는다.
-- 초대받은 친구에게 더 주는 것은 없다(가입 크레딧 그대로).
--
-- webtoon_invite_code : 사람마다 초대 코드 하나. 링크는 /webtoon?ref=<code>.
-- webtoon_invite      : 누가 누구를 데려왔나. 피초대자 한 명에 한 줄 — 두 코드로 들어와도 처음 것만 남는다.
--   signup_rewarded   : 가입 보상을 줬나. 한 달 상한(기본 10명)을 넘긴 줄은 false 로 남고 완성 보상도 안 준다.
--   completed_at      : 피초대자가 웹툰을 처음 완성한 시각. 완성 보상은 이 칸이 채워질 때 한 번만.
-- users 를 가리키지만 외래키는 안 건다(공통 모듈과 강하게 묶지 않는 이 저장소 규칙). 탈퇴 정리(WebtoonPurge)가 지운다.

CREATE TABLE IF NOT EXISTS webtoon_invite_code (
    user_id     BIGINT PRIMARY KEY,
    code        VARCHAR(16) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_webtoon_invite_code_code ON webtoon_invite_code (code);

CREATE TABLE IF NOT EXISTS webtoon_invite (
    id                BIGSERIAL PRIMARY KEY,
    inviter_id        BIGINT NOT NULL,
    invitee_id        BIGINT NOT NULL,
    code              VARCHAR(16) NOT NULL,
    browser_uid       VARCHAR(64),
    signed_up_at      TIMESTAMPTZ NOT NULL,
    signup_rewarded   BOOLEAN NOT NULL DEFAULT FALSE,
    completed_at      TIMESTAMPTZ,
    complete_rewarded BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_webtoon_invite_invitee ON webtoon_invite (invitee_id);
CREATE INDEX IF NOT EXISTS idx_webtoon_invite_inviter ON webtoon_invite (inviter_id, signed_up_at);
