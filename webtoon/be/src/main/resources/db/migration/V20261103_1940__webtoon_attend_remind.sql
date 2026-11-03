-- haeun · 2026-11-03 19:40 KST
-- 목적: 다음 화 크레딧이 모자란 사람이 「출석으로 모아서 만들기」를 고르면 알림을 받겠다고 한 기록.
--
-- 다음 화 화면에서 크레딧이 모자라면 두 갈래를 보여 준다 — 지금 충전하기, 출석으로 모으기.
-- 출석을 고른 사람에게는 날마다 출석하라고, 크레딧이 다 모이면 그 화를 만들 수 있다고
-- 알린다(AttendRemindSweep). 알림은 이 사람이 구독한 웹푸시, 다 모였을 때 한 번은 메일.
--
-- ── 한 줄이 한 사람이다 ──────────────────────────────────────────
-- 사람마다 지금 기다리는 화 하나만 둔다. 다른 작품에서 다시 고르면 그 화로 고쳐 쓴다.
-- 다 모여 알림을 보냈거나 사람이 껐으면 done_at 이 찍히고 더 안 보낸다.
--
-- user_id 에 외래키를 안 거는 이유는 webtoon_push_subscription 과 같다 — 탈퇴 정리
-- (WebtoonPurge)가 이 표를 직접 지운다.
CREATE TABLE IF NOT EXISTS webtoon_attend_remind (
    user_id       BIGINT       PRIMARY KEY,
    -- 이어 만들 앞 화의 run_id. 누르면 그 화의 「다음 화 이어 만들기」로 간다.
    run_id        VARCHAR(64)  NOT NULL,
    -- 만들려는 화 번호와 그때 필요했던 크레딧
    episode       INT          NOT NULL,
    cost          INT          NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    -- 마지막으로 출석 알림을 보낸 날(한국 날짜). 하루에 한 번만 보낸다.
    last_sent_on  DATE,
    done_at       TIMESTAMPTZ
);
