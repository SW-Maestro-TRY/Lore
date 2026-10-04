-- haeun · 2026-09-26 13:29 KST
-- 목적: 예시 작품의 작업 줄을 끝난 것(DONE)으로 옮긴다.
--
-- 예시 시드(ExampleWorks)가 작업 줄을 QUEUED 로 넣어 왔다. 예시는 실제로 도는
-- 작업이 아니라 영영 끝나지 않고 줄에 서 있었고, 그래서 모든 사람에게
-- 「앞에 대기자 9명 · 약 27분」이 떴으며 하루 비용 상한도 그만큼 미리 잡혔다
-- (JobQueue.ahead · reserved). 시드는 이제 DONE 으로 넣는다(WebtoonJob.seeded).
--
-- 상태·단계·끝난 때만 바꾼다. 작품(webtoon_work)·그림·이야기(webtoon_story)는
-- 건드리지 않는다.
update webtoon_job
   set status      = 'DONE',
       stage       = 'BIND',
       finished_at = coalesce(finished_at, updated_at),
       updated_at  = now()
 where browser_uid = 'lore-example-seed'
   and status      = 'QUEUED';
