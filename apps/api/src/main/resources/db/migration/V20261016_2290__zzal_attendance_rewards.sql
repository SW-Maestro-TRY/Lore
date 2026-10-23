ALTER TABLE zzal_pet ADD COLUMN attendance_night_cycle_at timestamptz;
CREATE TABLE zzal_awake_visit (
 id bigserial PRIMARY KEY, pet_id bigint NOT NULL REFERENCES zzal_pet(id), program_version varchar(40) NOT NULL,
 cycle_key varchar(100) NOT NULL, event_id varchar(180) NOT NULL UNIQUE, observed_at timestamptz NOT NULL,
 UNIQUE(pet_id,program_version,cycle_key)
);
CREATE TABLE zzal_custom_bundle_job (
 id bigserial PRIMARY KEY, pet_id bigint NOT NULL REFERENCES zzal_pet(id), program_version varchar(40) NOT NULL,
 bundle_version varchar(40) NOT NULL, snapshot_json text NOT NULL, input_hash varchar(64) NOT NULL,
 state varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','CLAIMED','GENERATED','FAILED')),
 claim_token varchar(100), output_dir text UNIQUE, cli_started_at timestamptz, execution_id varchar(200), manifest_hash varchar(64), error_code varchar(100), created_at timestamptz NOT NULL,
 UNIQUE(pet_id,program_version,bundle_version)
);
CREATE TABLE zzal_custom_bundle_asset (
 id bigserial PRIMARY KEY, job_id bigint NOT NULL REFERENCES zzal_custom_bundle_job(id), motion_order int NOT NULL CHECK(motion_order BETWEEN 1 AND 8),
 asset_key text NOT NULL, asset_hash varchar(64) NOT NULL, auto_check varchar(20) NOT NULL CHECK(auto_check IN ('PASS','FAIL')),
 human_review varchar(20) NOT NULL CHECK(human_review IN ('UNREVIEWED','APPROVED','REJECTED')), published boolean NOT NULL DEFAULT false,
 UNIQUE(job_id,motion_order)
);
CREATE TABLE zzal_attendance_reward_slot (
 id bigserial PRIMARY KEY, pet_id bigint NOT NULL REFERENCES zzal_pet(id), program_version varchar(40) NOT NULL,
 slot_key varchar(40) NOT NULL, eligible_day int NOT NULL, state varchar(20) NOT NULL DEFAULT 'ELIGIBLE' CHECK(state IN ('ELIGIBLE','GRANTED')),
 asset_id bigint REFERENCES zzal_custom_bundle_asset(id), asset_key text, granted_at timestamptz,
 UNIQUE(pet_id,program_version,slot_key)
);
