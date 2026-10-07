-- 1층 우선 부화 + 2층 배경 굽기(#696).
--
-- ★ 무엇이 바뀌나 — 부화 완료(ALIVE)가 1층(격자 1장·8종)에서 끝나고, 2층(격자 2장·8종)은
--   부화 뒤 별도 작업(kind = LAYER2)으로 굽는다. 펫에 2층 상태 칸을 둔다.
--
-- ★ 칸은 전부 nullable 이거나 기본값이 있다(ZzalPet 컬럼 추가 규칙) — 이미 행이 있는 표다.
--
-- ★ 작업 표의 kind 값 목록에 LAYER2 를 <b>더한다</b>(기존 HATCH·MOTION 그대로). 값을 늘리는 것이라
--   기존 행에는 영향이 없다. 값 목록 CHECK 는 ddl-auto 가 못 고치므로 여기서 다시 건다.

ALTER TABLE zzal_gen_job DROP CONSTRAINT IF EXISTS zzal_gen_job_kind_check;
ALTER TABLE zzal_gen_job ADD CONSTRAINT zzal_gen_job_kind_check
    CHECK ((kind)::text = ANY ((ARRAY['HATCH'::character varying, 'MOTION'::character varying,
                                      'LAYER2'::character varying])::text[]));

-- 2층 상태. PENDING(굽기 전) · RUNNING(굽는 중) · READY(공개 가능) · FAILED(재시도 소진·관리자 표시)
ALTER TABLE zzal_pet ADD COLUMN layer2_status varchar(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE zzal_pet ADD CONSTRAINT zzal_pet_layer2_status_check
    CHECK ((layer2_status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying,
                                               'READY'::character varying, 'FAILED'::character varying])::text[]));
-- 이번 판의 시도 수(관리자 재시도는 0 부터 다시 센다)
ALTER TABLE zzal_pet ADD COLUMN layer2_attempts integer NOT NULL DEFAULT 0;
-- 마지막 실패 사유(게이트 거부 메시지 앞부분 등). 관리자 목록에만 나간다
ALTER TABLE zzal_pet ADD COLUMN layer2_last_error varchar(500);
-- 2층 상태가 마지막으로 바뀐 시각(오래 PENDING 인 것을 관리자 목록에 올릴 때 본다)
ALTER TABLE zzal_pet ADD COLUMN layer2_updated_at timestamp(6) with time zone;
-- READY 를 사용자에게 알린 시각("○○를 배웠어요" 폭죽을 한 번만 내기 위한 표식)
ALTER TABLE zzal_pet ADD COLUMN layer2_announced_at timestamp(6) with time zone;
-- 통과했지만 결함이라 관리자가 손으로 목록에 넣었나(펫23 빈 칸 등)
ALTER TABLE zzal_pet ADD COLUMN layer2_flagged boolean NOT NULL DEFAULT false;
-- 관리자가 올린 2층 후보 목록(후보id:격자키:게이트, 쉼표 구분). 고르면 비운다
ALTER TABLE zzal_pet ADD COLUMN layer2_candidates varchar(2000);
-- 관리자가 올린 1층 후보 목록(같은 형식)
ALTER TABLE zzal_pet ADD COLUMN layer1_candidates varchar(2000);

-- ── 백필 ──────────────────────────────────────────────────────────────
-- 옛 흐름에서는 부화 완료(hatched_at)가 1층·2층 후처리를 모두 끝내야 찍혔다 → 2층 webp 가 있다 → READY.
--   알림 표식도 함께 찍는다(이미 보던 아이에게 폭죽을 다시 터뜨리지 않는다).
UPDATE zzal_pet SET layer2_status = 'READY', layer2_updated_at = now(), layer2_announced_at = now()
 WHERE hatched_at IS NOT NULL;
-- 부화에 실패한 알 → 2층도 없다 → FAILED(관리자 목록에는 1층 실패로 따로 나간다).
UPDATE zzal_pet SET layer2_status = 'FAILED', layer2_updated_at = now()
 WHERE hatched_at IS NULL AND phase = 'FAILED';
-- DRAFT·HATCHING 은 PENDING 그대로 — 새 흐름이 1층 완료 뒤 2층을 굽는다.
