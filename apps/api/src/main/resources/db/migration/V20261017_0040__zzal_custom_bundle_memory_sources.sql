-- Immutable plans are separate from historical snapshot/receipt bytes.
ALTER TABLE zzal_custom_bundle_job ADD COLUMN source_plan_json text;
ALTER TABLE zzal_custom_bundle_job ADD COLUMN source_plan_hash varchar(64);
CREATE TABLE zzal_custom_bundle_source (
 job_id bigint NOT NULL REFERENCES zzal_custom_bundle_job(id),
 pet_id bigint NOT NULL REFERENCES zzal_pet(id),
 fact_id bigint NOT NULL,
 fact_revision int NOT NULL,
 provenance_key varchar(64) NOT NULL,
 state varchar(16) NOT NULL CHECK(state IN ('RESERVED','USED','RELEASED')),
 selected_at timestamptz NOT NULL,
 used_at timestamptz,
 PRIMARY KEY(job_id,fact_id),
 CHECK((state='USED')=(used_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_zzal_bundle_active_source ON zzal_custom_bundle_source(pet_id,provenance_key)
 WHERE state IN ('RESERVED','USED');
