-- sanghoon416 · 2026-10-28 20:39 KST
-- 목적: 관리자 재시도 요청(부화 · 맞춤 8종). 설계 spec-zzal-admin-failures-retry-20261027.
-- 추가만 한다 — 기존 표는 건드리지 않는다.
CREATE TABLE zzal_admin_retry (
  id             bigserial    PRIMARY KEY,
  kind           varchar(10)  NOT NULL CHECK (kind IN ('HATCH','CUSTOM')),
  target_id      bigint       NOT NULL,              -- HATCH = zzal_pet.id, CUSTOM = zzal_custom_bundle_job.id
  pet_id         bigint       NOT NULL REFERENCES zzal_pet(id),
  auto           boolean      NOT NULL DEFAULT false, -- 서버가 만든 자동 CLI 요청(결정 8)
  memo           varchar(500),
  requested_by   bigint,                              -- 관리자 users.id, 자동이면 NULL
  requested_at   timestamptz  NOT NULL,
  picked_at      timestamptz,                         -- 러너가 집은 시각(claim)
  finished_at    timestamptz,                         -- 러너 결과 도착 · 취소 시각
  result         varchar(12)  CHECK (result IN ('CANDIDATES','NONE','CANCELLED','APPLIED','APPLY_FAILED')),
  note           varchar(2000),                       -- 러너 메모 · 반영 실패 사유
  candidate_keys jsonb,                               -- 러너가 올린 키 · 서버 게이트 결과
  applied_at     timestamptz,
  applied_by     bigint,                              -- 사람이 고르면 users.id, 자동 반영이면 NULL
  applied_choice varchar(100),
  CONSTRAINT ck_zzal_admin_retry_auto_requester CHECK (auto = (requested_by IS NULL))
);
-- 같은 대상에 열린 요청(아직 결과 없음 · 후보 도착 후 고르기 전)은 하나만.
CREATE UNIQUE INDEX ux_zzal_admin_retry_open ON zzal_admin_retry (kind, target_id)
  WHERE result IS NULL OR result = 'CANDIDATES';
-- 자동 CLI 는 대상당 1회.
CREATE UNIQUE INDEX ux_zzal_admin_retry_auto ON zzal_admin_retry (kind, target_id) WHERE auto;
CREATE INDEX ix_zzal_admin_retry_pet ON zzal_admin_retry (pet_id, requested_at DESC);
