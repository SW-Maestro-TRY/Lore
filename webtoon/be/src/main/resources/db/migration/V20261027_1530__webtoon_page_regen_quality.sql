-- 다시 그리기 화질(2026-10-27) — 편집실에서 작품과 다른 화질로 한 장을 다시 그릴 때 적는다. 비어 있으면 작품 화질 그대로다.
alter table webtoon_page_regen add column if not exists quality varchar(10);
