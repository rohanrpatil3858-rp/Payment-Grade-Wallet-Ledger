-- Milestone 4: tables for the nightly reconciliation job.

-- One row per background job that must never run twice at once. SELECT ... FOR UPDATE NOWAIT
-- on this row is how we enforce "only one run at a time".
CREATE TABLE job_locks (
    job_name TEXT PRIMARY KEY
);

INSERT INTO job_locks (job_name) VALUES ('reconciliation');

-- One row per reconciliation run: when it started/finished, overall status, and summary counts.
CREATE TABLE reconciliation_runs (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    status        TEXT NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'completed', 'failed')),
    checks_run    INT NOT NULL DEFAULT 0,
    issues_found  INT NOT NULL DEFAULT 0
);

-- One row per problem found in a given run. entity_id identifies which transfer/account the
-- problem is about (NULL for a check that isn't about one specific entity).
CREATE TABLE reconciliation_issues (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id         BIGINT NOT NULL REFERENCES reconciliation_runs (id),
    check_name     TEXT NOT NULL,
    entity_id      BIGINT,
    expected_value BIGINT,
    actual_value   BIGINT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_reconciliation_issues_run_id ON reconciliation_issues (run_id);
