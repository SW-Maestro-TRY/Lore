-- haeun · 2026-10-27 01:36 KST
-- 목적: 예시 캐릭터에 「클로에」를 더한다.
--
-- 예시 캐릭터 시더(BuiltinCharacters)는 기본 제공이 하나도 없을 때만 새 이름을 심는다 — 이미 심은 DB
-- (운영 · staging · dev)에는 SEEDS 에 줄을 더해도 안 들어간다. 대신 「이름은 있는데 그림이 빈 줄」은
-- 뜰 때마다 그림을 채운다. 그래서 여기서 그림 없는 빈 줄만 넣고, 그림(ex-chloe-1.jpg)은 서버가 뜰 때
-- 시더가 그 환경의 창고에 올려 채운다.
--
-- 기본 제공이 아직 하나도 없는 새 DB 에서는 아무것도 안 한다 — 그때는 시더가 SEEDS 전부를 심는다.
-- 이미 클로에가 있으면 건너뛴다.
INSERT INTO webtoon_character (public_id, name, description, source, status, created_at, updated_at)
SELECT substr(md5('builtin-chloe'), 1, 20), '클로에',
       '겨울 무도회에서도 레이스 부채를 놓지 않는다. 숨이 가빠지면 부채부터 펼친다.',
       'BUILTIN', 'READY', now(), now()
WHERE EXISTS (SELECT 1 FROM webtoon_character WHERE source = 'BUILTIN')
  AND NOT EXISTS (SELECT 1 FROM webtoon_character WHERE source = 'BUILTIN' AND name = '클로에');
