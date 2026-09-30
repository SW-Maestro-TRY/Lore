-- haeun · 2026-10-01 01:23 KST
-- 목적: 실패한 작업이 왜 실패했는지를 전부 남긴다(#531).
--   error 칸은 사람에게 보여 줄 한 줄이라, 실제로 무엇이 터졌는지는 서버 로그에만
--   스쳐 지나갔다. 서버가 다시 뜨면 진행 기록(메모리)도 사라져서 나중에 찾을 수 없었다.
--   fail_stage  — 어느 걸음에서(STORY · SHEET · SHEET_IMAGE · PAGE_IMAGE …)
--   fail_code   — 무슨 종류로(image_safety · photo_missing · error · cancelled · server_restart)
--   fail_detail — 원문 에러와 하네스 출력 끝부분
-- 옛 작업과 잘 끝난 작업은 셋 다 NULL 이다.
ALTER TABLE webtoon_job
    ADD COLUMN fail_stage varchar(30),
    ADD COLUMN fail_code varchar(30),
    ADD COLUMN fail_detail text;
