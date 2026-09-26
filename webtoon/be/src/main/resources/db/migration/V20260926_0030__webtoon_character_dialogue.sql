-- haeun · 2026-09-26 00:30 KST
-- 목적: 「캐릭터 만들어보기」 카드의 대사를 한 줄(quote)에서 두세 줄(dialogue)로 늘린다.
--       한 줄에 「누구|내 것인가|화면 어느 쪽|말」 을 탭으로 잇고, 줄바꿈으로 줄을 나눈다.
--       옛 카드는 이 칸이 비어 있고 quote 만 있다 — 화면이 둘 다 읽는다.
ALTER TABLE webtoon_character
    ADD COLUMN dialogue text;
