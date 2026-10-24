-- haeun · 2026-10-24 23:21 KST
-- 목적: #780 사용자가 가진 캐릭터 그림을 AI 생성 없이 그대로 등록한다(원본 캐릭터).
--
-- source 에 두 값을 더한다.
--   UPLOAD           처음부터 원본 그림으로 등록한 캐릭터. 생성을 안 했으니 생성 횟수 한도에 안 센다.
--   REPLACED_UPLOAD  AI 로 만든 캐릭터의 그림을 원본으로 바꾼 것. 처음 만들 때 생성을 했으므로
--                    한도에는 계속 센다 — 그래서 UPLOAD 와 따로 둔다.
--
-- 제약 이름은 baseline(apps/api V1)이 만든 webtoon_character_source_check 그대로다(로컬 · 운영 확인).
ALTER TABLE webtoon_character DROP CONSTRAINT IF EXISTS webtoon_character_source_check;
ALTER TABLE webtoon_character ADD CONSTRAINT webtoon_character_source_check
    CHECK (source IN ('PHOTO', 'PROMPT', 'BUILTIN', 'UPLOAD', 'REPLACED_UPLOAD'));
