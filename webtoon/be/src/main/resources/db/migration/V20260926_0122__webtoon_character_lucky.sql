-- haeun · 2026-09-26 KST
-- 목적: 「캐릭터 만들어보기」에서 아주 낮은 확률로 종까지 바뀐 뽑기(당첨)를 카드에 남긴다(#331).
--       당첨을 표시하지 않으면 사진을 올렸는데 개가 나온 사람은 고장으로 읽는다.
ALTER TABLE webtoon_character
    ADD COLUMN lucky boolean NOT NULL DEFAULT false;
