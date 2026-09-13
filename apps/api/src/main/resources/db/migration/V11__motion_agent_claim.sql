-- 러너 일감에 집기를 붙인다 (P-14)


-- ── 맥미니(codex) 러너가 주문을 가져간 시각 ──────────────────────────────
--
-- ★★ 왜 필요한가 — 일감 목록(GET /agent/jobs)이 LOCAL_REQUESTED 를 읽기만 하고 상태를 한 글자도
--   안 바꿨다. 맥미니가 10분짜리 재생성을 굽는 동안 러너가 계속 폴링하면 매번 같은 motionId 를
--   받는다. 러너가 둘이면 둘 다 같은 판을 굽는다 — codex 구독 한도를 같은 그림에 N배로 태운다.
--   두 번째 업로드는 ZZAL_REGEN_NOT_REQUESTED 로 거절되지만 그림은 이미 다 구운 뒤다.
--
-- ★★ 있는 claimed_at 을 재활용하지 않는 이유 — 그 칸은 "서버가 굽기를 집었다"(QUEUED → BAKING)를
--   가리키고, 굽기가 실패해 LOCAL_REQUESTED 로 내려가도 지워지지 않고 남아 있다. 재활용하면
--   모든 주문이 "이미 누가 집었다" 로 보여 러너가 영영 빈손으로 돌아간다. 두 집기는 다른 사건이다.
--
-- ★ 빌려주는 것이지 영영 주는 것이 아니다 — 러너가 중간에 죽으면 아무도 안 올린다. 그래서
--   app.zzal.recovery.local-grace-minutes 보다 오래된 집기는 없는 것으로 치고 다시 내준다.
--   NULL = 아무도 안 가져갔다. 기존 행은 전부 그 상태로 시작하는 것이 맞다.

ALTER TABLE zzal_motion ADD COLUMN agent_claimed_at timestamp(6) with time zone;
