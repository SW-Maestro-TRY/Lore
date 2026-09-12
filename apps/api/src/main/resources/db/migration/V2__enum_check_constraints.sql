-- enum 에 값을 더했을 때 CHECK 제약을 다시 만든다.
--
-- ★ 왜 손으로 써야 하나 — Hibernate 는 enum 칸을 varchar + CHECK 제약으로 만든다.
--   ddl-auto: update 는 표와 칸은 따라오게 하지만 이미 있는 CHECK 제약은 갱신하지 않는다.
--   그래서 새 값을 넣는 순간 "violates check constraint" 로 죽는다.
--
-- ★ 무서운 점 — 빌드도 배포도 기동도 전부 통과한다. 그 값을 실제로 쓰는 호출에서만 터진다.
--   2026-09-09 회원가입(AGE_14)과 그림 등록(DRAFT)에서 실제로 겪었다.
--
-- ★★ 표가 있을 때만 돈다 — Flyway 는 Hibernate 보다 <b>먼저</b> 돈다. 빈 DB(새로 받은 팀원·CI)에서는
--    아직 표가 없어서, 감싸지 않으면 여기서 기동이 통째로 실패한다. 빈 DB 라면 할 일이 없다 —
--    Hibernate 가 표를 만들 때 엔티티의 새 값으로 제약을 만들어 주기 때문이다.

DO $$
BEGIN
    -- 만 14세 이상 동의를 따로 받는다. 나이는 법이 요구하는 별도 사실이라
    -- 이용약관 동의에 묻히면 "언제 무엇에 동의했나" 를 답할 수 없다.
    IF to_regclass('user_agreement') IS NOT NULL THEN
        ALTER TABLE user_agreement DROP CONSTRAINT IF EXISTS user_agreement_type_check;
        ALTER TABLE user_agreement ADD CONSTRAINT user_agreement_type_check
            CHECK (type IN ('AGE_14', 'TERMS', 'PRIVACY', 'MARKETING'));
    END IF;

    -- DRAFT = 그림만 올렸고 이름이 아직 없는 초안. 이름을 짓는 74초 동안 시트를 미리 굽는다.
    IF to_regclass('zzal_pet') IS NOT NULL THEN
        ALTER TABLE zzal_pet DROP CONSTRAINT IF EXISTS zzal_pet_phase_check;
        ALTER TABLE zzal_pet ADD CONSTRAINT zzal_pet_phase_check
            CHECK (phase IN ('DRAFT', 'HATCHING', 'ALIVE', 'FAILED', 'DEAD'));
    END IF;
END $$;
