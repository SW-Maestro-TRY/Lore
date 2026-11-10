-- Versioned account-level guide receipts. No backfill or existing lifecycle changes.
CREATE TABLE zzal_returning_guide (
    user_id bigint NOT NULL,
    version varchar(32) NOT NULL,
    pet_id bigint NOT NULL,
    step smallint NOT NULL DEFAULT 0 CHECK (step BETWEEN 0 AND 5),
    completed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, version),
    CHECK (completed_at IS NULL OR step = 5)
);
