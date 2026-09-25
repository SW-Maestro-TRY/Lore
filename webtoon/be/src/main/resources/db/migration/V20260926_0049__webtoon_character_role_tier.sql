-- haeun · 2026-09-26 00:49 KST
-- 목적: 「캐릭터 만들어보기」에서 코드가 굴린 자리의 무게(중심 · 곁 · 스쳐감 · 뜬금)를 카드에 남긴다.
--       하네스는 처음부터 role_tier 를 내보내고 있었는데 저장하지 않아 화면에 못 보였다.
ALTER TABLE webtoon_character
    ADD COLUMN role_tier varchar(20);
