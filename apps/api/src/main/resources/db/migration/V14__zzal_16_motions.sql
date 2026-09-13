-- 기본 행동 16종 교체에 딸린 칸들.
--
-- ★ 전부 기본값이 있어 이미 있는 행이 걸리지 않는다(행이 있는 표에 더하는 칸은
--   nullable 이거나 기본값이 있어야 한다는 규칙).
-- ★ 소급 UPDATE 는 없다 — 새 카운터는 전부 0 에서 시작한다.

-- 간식 누적 — 그날 4개까지만 센다(5개째부터는 배탈이라 안 센다).
ALTER TABLE zzal_pet ADD COLUMN snacks integer DEFAULT 0 NOT NULL;

-- 손으로 깨운 밤잠의 수 — 아침 자동 기상·튜토리얼 낮잠은 빠진다.
ALTER TABLE zzal_pet ADD COLUMN wakes integer DEFAULT 0 NOT NULL;

-- 말투·장르 — 대사 톤에만 쓰는 자유 입력. 그림 생성에는 안 들어간다.
--
-- ★ 길이는 ZzalRules.TONE_MAX_CHARS / GENRE_MAX_CHARS 와 같아야 한다. 갈리면 요청 검증은
--   통과하고 저장에서 터져 사용자에게 500 만 간다(세계관 칸에서 실제로 났다).
ALTER TABLE zzal_pet ADD COLUMN tone character varying(32);
ALTER TABLE zzal_pet ADD COLUMN genre character varying(32);
