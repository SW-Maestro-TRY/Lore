-- haeun · 2026-10-27 21:48 KST
-- 목적: 다시 그리기·이어 그리기 값을 낸 사람과 장부 번호를 기록에 남긴다(#864).
--       전에는 서버 메모리에만 적어서, 배포·재시작 때 줄에 있던 장은 못 그리고도 값을 못 돌려줬다.
--       무료(빈 장)와 옛 기록은 비어 있다.
alter table webtoon_page_regen add column if not exists paid_by bigint;
alter table webtoon_page_regen add column if not exists paid_ref varchar(200);
