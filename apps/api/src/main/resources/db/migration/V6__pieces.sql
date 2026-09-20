-- 3층 조각 (정본 6장 · 세는 법 1.9) 과 간식 규칙 변경 (1.9)


-- ── 1) 조각 네 칸 ─────────────────────────────────────────────────────────
--
-- ★ 왜 pet 표에 칸을 더하지 않고 제 표를 두나 — 조각은 3층부터만 뜻이 있어서,
--   pet 표에 열한 칸을 더하면 대부분의 행에서 전부 0 인 칸이 열한 개가 된다.
--
-- ★★ 왜 길마다 따로 세나 — 한 칸에 길이 둘이다(청결 = 목욕 2회 또는 청소 5회).
--   한 칸에 횟수를 하나만 두면 "청소 3회 + 목욕 1회 = 4회" 가 목욕 목표(2)를 넘겨
--   목욕을 한 번밖에 안 했는데도 도장이 찍힌다.
--
-- ★ 리셋이 없다(1.8) — 요구량 자체가 이틀치라 하루마다 지우면 영원히 못 채운다.
--   0 으로 돌아가는 때는 둘뿐이다: 그 칸에 도장이 찍히는 순간, 그리고 네 칸이 다 찬 판의 다음 기상.

CREATE TABLE zzal_piece (
    pet_id bigint PRIMARY KEY,

    -- 길마다 하나씩. 도장이 찍히면 그 칸의 길이 모두 0 으로 돌아간다(넘친 만큼은 버린다).
    feed_count integer DEFAULT 0 NOT NULL,
    snack_count integer DEFAULT 0 NOT NULL,
    game_count integer DEFAULT 0 NOT NULL,
    clean_count integer DEFAULT 0 NOT NULL,
    bath_count integer DEFAULT 0 NOT NULL,
    pet_count integer DEFAULT 0 NOT NULL,
    chat_count integer DEFAULT 0 NOT NULL,

    -- 도장 네 칸
    food_done boolean DEFAULT false NOT NULL,
    play_done boolean DEFAULT false NOT NULL,
    clean_done boolean DEFAULT false NOT NULL,
    bond_done boolean DEFAULT false NOT NULL
);


-- ── 2) 펫 표 — 조각 쪽지 두 칸 ────────────────────────────────────────────
--
-- ★ 조각은 제 표에 있고 엔티티는 표를 모른다(zzal 은 엔티티끼리 참조하지 않는다).
--   그래서 펫은 "되돌릴 때가 됐다 · 선물을 줄 때가 됐다" 는 쪽지만 남기고 서비스가 줄을 만진다.
--   이미 쓰던 방식이다(pending_night_scene_at 과 같다).

ALTER TABLE zzal_pet ADD COLUMN piece_reset_pending boolean DEFAULT false NOT NULL;
ALTER TABLE zzal_pet ADD COLUMN piece_bonus_pending boolean DEFAULT false NOT NULL;


-- ── 3) 없어진 칸 ─────────────────────────────────────────────────────────
--
-- piece_streak · last_night_piece_streak — "조각 4개를 며칠 연속" 이 없어졌다(1.8).
--   요구량 자체가 이틀치라 연속을 셀 이유가 없다.
--
-- snack_streak — "다른 행동 없이 연달아 5개면 배탈" 이 "그날 5개째면 배탈" 로 바뀌었다(1.9).
--   옛 규칙은 사이에 밥을 한 번만 끼워도 연속이 끊겨 하루에 열 개도 먹일 수 있었다.
--   그날 개수는 이미 today_snacks 가 세고 있어 새 칸이 필요 없다.

ALTER TABLE zzal_pet DROP COLUMN piece_streak;
ALTER TABLE zzal_pet DROP COLUMN last_night_piece_streak;
ALTER TABLE zzal_pet DROP COLUMN snack_streak;
