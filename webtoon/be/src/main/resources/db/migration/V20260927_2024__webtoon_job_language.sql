-- haeun · 2026-09-27 20:24 KST
-- 목적: 웹툰을 어느 언어로 만들지(ko·en·ja) 작업에 남긴다(#472). 화질(quality)과 같은
--       이유다 — 한 장을 다시 그릴 때도 처음 고른 언어로 그려야 한다. 옛 작업에는 값이
--       없으니 NULL 이고, 읽는 쪽(WebtoonLanguage.normalize)이 ko 로 돌린다.
ALTER TABLE webtoon_job
    ADD COLUMN language varchar(10);
