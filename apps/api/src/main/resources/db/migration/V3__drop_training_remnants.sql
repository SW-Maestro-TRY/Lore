-- 폐기된 "훈련" 규칙의 잔재를 지운다.
--
-- ★ 왜 남아 있었나 — 엔티티에서 빼면 새 펫 INSERT 가 "null 불가" 로 죽는데, 그게 서버가
--   정상 기동한 뒤 실제 부화 때만 드러난다. 그래서 0 으로만 채우는 매핑을 남겨 두고
--   컬럼을 지울 때까지 미뤄 왔다. 이제 지운다.
--
-- ★★ 표가 있을 때만 돈다 — 빈 DB 에서는 아직 zzal_pet 이 없다(V1 주석 참조).
--    그 경우 지울 것도 없다 — Hibernate 가 만드는 표에는 이 칸들이 애초에 없다.
--
-- ★ 되돌릴 수 없다 — 다만 이 칸들은 어떤 코드도 읽지 않고, 값도 전부 0 이다.

DO $$
BEGIN
    IF to_regclass('zzal_pet') IS NOT NULL THEN
        ALTER TABLE zzal_pet DROP COLUMN IF EXISTS train_stack;
        ALTER TABLE zzal_pet DROP COLUMN IF EXISTS train_gain;
        ALTER TABLE zzal_pet DROP COLUMN IF EXISTS train_started_at;

        -- 마지막으로 돌본 시각과 헷갈리던 칸. 지금은 last_cared_at 하나만 쓴다.
        ALTER TABLE zzal_pet DROP COLUMN IF EXISTS care_started_at;

        -- v1 의 해금 개수. v2 는 zzal_motion 행이 정본이라 셀 필요가 없다.
        ALTER TABLE zzal_pet DROP COLUMN IF EXISTS unlocked_count;
    END IF;
END $$;
