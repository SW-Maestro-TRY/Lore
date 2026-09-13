-- 조각 완성을 실제로 썼는지 (정본 6장 · 1.9)
--
-- ★★ 왜 필요한가 — 네 칸이 찬 판을 비우는 자리는 다음 기상이고, 굽기를 거는 자리는 밤이다.
--    둘 사이가 벌어져 양쪽으로 샜다.
--
--    두 번 걸림 — 직접 재워 한 번 걸고, 같은 밤 스위프(NightSweep.planAll)가 또 건다.
--                 판이 아직 안 비워져 isComplete 가 계속 참이다. 심화 둘이 구워지고 돈도 검수도 두 배.
--                 옛 코드는 piece_streak 을 소모해 막았는데 1.9 에서 그 칸이 사라지며 같이 없어졌다.
--    안 걸림    — 직접 재우지 않아 23:00 자동 취침으로 넘어가고 스위프도 꺼져 있으면,
--                 아무것도 안 걸린 채 다음 기상에 판이 비워진다. 이틀 걸려 채운 조각이 조용히 사라진다.
--
-- ★ 그래서 "쓰인 완성만" 비운다. 즉시 굽기가 붙으면 굽기 시작 지점에서 이 칸을 찍는다.

ALTER TABLE zzal_piece ADD COLUMN consumed boolean DEFAULT false NOT NULL;


-- ── 안 쓰는 칸 넷 ─────────────────────────────────────────────────────────
--
-- 옛 조각 구현이 남긴 칸이다. 증가하고 매일 리셋되지만 <b>읽는 곳이 없다.</b>
-- 주석이 아직 옛 요구량("밥 조각 = 2회" · "청결 조각 = 청소 1회 또는 목욕 1회")을 말하고 있어,
-- 조각을 고치러 온 사람이 zzal_piece 가 아니라 여기를 고칠 수 있다.
--
-- ★ today_snacks 는 남긴다 — 그날 5개째 배탈 판정에 실제로 쓴다(정본 1.9).

ALTER TABLE zzal_pet DROP COLUMN today_feeds;
ALTER TABLE zzal_pet DROP COLUMN today_cleans;
ALTER TABLE zzal_pet DROP COLUMN today_game_wins;
ALTER TABLE zzal_pet DROP COLUMN today_chat_answers;


-- ── 굽기 일감의 불변식 ───────────────────────────────────────────────────
--
-- 주석에만 있고 DB 가 지키지 않던 것 둘을 제약으로 옮긴다.
-- 아직 아무도 이 표에 쓰지 않아 지금이 가장 싸다 — 굽기를 붙인 뒤에 넣으려면
-- 이미 들어간 중복을 먼저 치워야 한다.

-- "한 펫이 같은 동작을 두 번 굽지 않는다"(ZzalBake.motionKey 주석)
-- exists 로 보고 insert 하면 두 요청이 동시에 exists=false 를 보고 각각 넣을 수 있다.
-- 같은 동작을 두 번 굽고 검수 화면에도 같은 동작이 둘로 뜬다.
ALTER TABLE zzal_bake ADD CONSTRAINT uk_zzal_bake_pet_motion UNIQUE (pet_id, motion_key);

-- "한 일감에 최대 하나 chosen"(ZzalBakeCandidate.chosen 주석)
-- 관리자 화면이 두 번 눌리면 후보 둘이 chosen 이 되고, 어느 판이 나갔는지가 갈린다.
-- 부분 인덱스라 chosen=false 인 후보는 몇 개든 상관없다.
CREATE UNIQUE INDEX uk_zzal_bake_candidate_chosen
    ON zzal_bake_candidate (bake_id) WHERE chosen;
