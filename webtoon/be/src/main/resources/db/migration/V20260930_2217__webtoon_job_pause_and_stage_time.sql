-- haeun · 2026-09-30 22:17 KST
-- 목적: 진행 화면의 경과 시간·남은 시간을 사실대로 적으려고(#509) 두 가지를 남긴다.
--   paused_seconds · paused_at — 사람이 이야기를 고르거나 시트를 확인하느라 멈춘 시간.
--       경과 시간과 남은 시간 계산에서 이만큼을 뺀다. 지금 멈춰 있으면 paused_at 에
--       멈춘 때가 있고, 다시 가면 그 차이를 paused_seconds 에 더하고 비운다.
--   stage_at — 지금 걸음(story·sheet·pages·bind)을 시작한 때. updated_at 은 걸음과
--       상관없는 쓰기(알림 주소 등)에도 바뀌어서 걸음 안에서 얼마나 지났는지를 못 잰다.
-- 옛 작업은 0 · NULL 이고, 읽는 쪽이 NULL 이면 updated_at 으로 대신한다.
ALTER TABLE webtoon_job
    ADD COLUMN paused_seconds bigint NOT NULL DEFAULT 0,
    ADD COLUMN paused_at timestamptz,
    ADD COLUMN stage_at timestamptz;
