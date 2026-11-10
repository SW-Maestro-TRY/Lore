-- 생성 실패 이유 코드 5개를 더한다(설계 1-2, 계획서 P1-2).
--
-- ★ 왜 손으로 써야 하나 — Hibernate 는 enum 칸의 이미 있는 CHECK 제약을 고치지 않는다(V2 주석).
--   그래서 새 값을 처음 저장하는 순간에만 "violates check constraint" 로 터진다.
--
-- 새 값
--   INTERRUPTED        서버 종료·끼어들기로 끊김
--   PROVIDER_ERROR     OpenAI 5xx·네트워크 오류
--   QUOTA              429·잔액·한도
--   GATE_REJECTED      격자 구조 게이트 탈락
--   POSTPROCESS_FAILED 후처리 스크립트 비정상 종료
--
-- ★ 옛 행의 UNKNOWN 은 다시 분류하지 않는다(설계 1-2 "이번에 안 하는 것").
-- ★★ 표가 있을 때만 돈다 — 빈 DB 에서는 Hibernate 가 엔티티의 새 값으로 제약을 만든다(V2 와 같은 이유).

DO $$
BEGIN
    IF to_regclass('zzal_gen_job') IS NOT NULL THEN
        ALTER TABLE zzal_gen_job DROP CONSTRAINT IF EXISTS zzal_gen_job_error_code_check;
        ALTER TABLE zzal_gen_job ADD CONSTRAINT zzal_gen_job_error_code_check
            CHECK (error_code IN ('TIMEOUT', 'MODERATION_BLOCKED', 'UNKNOWN',
                                  'INTERRUPTED', 'PROVIDER_ERROR', 'QUOTA', 'GATE_REJECTED', 'POSTPROCESS_FAILED'));
    END IF;

    IF to_regclass('zzal_gen_step') IS NOT NULL THEN
        ALTER TABLE zzal_gen_step DROP CONSTRAINT IF EXISTS zzal_gen_step_error_code_check;
        ALTER TABLE zzal_gen_step ADD CONSTRAINT zzal_gen_step_error_code_check
            CHECK (error_code IN ('TIMEOUT', 'MODERATION_BLOCKED', 'UNKNOWN',
                                  'INTERRUPTED', 'PROVIDER_ERROR', 'QUOTA', 'GATE_REJECTED', 'POSTPROCESS_FAILED'));
    END IF;
END $$;
