-- 기본 행동 16종 교체에 딸린 칸들.
--
-- ★ 전부 기본값이 있어 이미 있는 행이 걸리지 않는다(행이 있는 표에 더하는 칸은
--   nullable 이거나 기본값이 있어야 한다는 규칙).
-- ★ 소급 UPDATE 는 없다 — 새 카운터는 전부 0 에서 시작한다.

-- 간식 누적 — 그날 4개까지만 센다(5개째부터는 배탈이라 안 센다).
ALTER TABLE zzal_pet ADD COLUMN snacks integer DEFAULT 0 NOT NULL;

-- 손으로 깨운 밤잠의 수 — 아침 자동 기상·튜토리얼 낮잠은 빠진다.
ALTER TABLE zzal_pet ADD COLUMN wakes integer DEFAULT 0 NOT NULL;
