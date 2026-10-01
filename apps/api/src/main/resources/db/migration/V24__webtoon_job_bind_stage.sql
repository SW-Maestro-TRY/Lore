-- 웹툰 만들기 진행률에 검수·합본 걸음(bind)을 더한다.
--
-- 전에는 페이지를 마지막 장까지 다 그리면 그 순간 pages 단계의 frac 이 1.0 이 되어
-- 진행률이 이미 100% 를 찍었는데, 실제로는 검수·합본(이어 붙이기)·업로드가 아직
-- 남아 있어 "100%인데 안 넘어간다" 는 체감으로 이어졌다(2026-09-23,
-- com.lore.webtoon.job.JobStage 참고). 그 사이에 걸음을 하나 더 두려면 여기서
-- 허용 값부터 늘려야 한다.
ALTER TABLE webtoon_job DROP CONSTRAINT webtoon_job_stage_check;
ALTER TABLE webtoon_job ADD CONSTRAINT webtoon_job_stage_check
    CHECK (((stage)::text = ANY ((ARRAY['STORY'::character varying, 'SHEET'::character varying, 'BOARD'::character varying, 'PAGES'::character varying, 'BIND'::character varying])::text[])));
