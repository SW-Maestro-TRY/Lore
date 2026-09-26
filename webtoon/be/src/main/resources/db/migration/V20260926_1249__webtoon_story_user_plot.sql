-- haeun · 2026-09-26 KST
-- 목적: 완성본의 줄거리(로그라인)를 주인이 고칠 수 있게 한다(#78). 제목(user_title)과
--       같은 규칙이다 — 모델이 지은 plot 은 덮어쓰지 않고 옆 칸에 사람이 고친 것을 두며,
--       비우면 다시 모델이 지은 줄거리로 돌아간다.
ALTER TABLE webtoon_story
    ADD COLUMN user_plot varchar(300);   -- 사람이 고친 줄거리(NULL 이면 plot 을 그대로 쓴다)
