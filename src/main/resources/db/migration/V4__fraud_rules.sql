-- Milestone 5: fraud rules and risk flags.

-- Transfer notes are user input (e.g. "for rent", or in testing, injection attempts). They are
-- always treated as plain data - never as instructions - when later shown to an LLM.
ALTER TABLE transfers ADD COLUMN note TEXT;

-- Thresholds live here, not in code, so they can be tuned without a deploy.
CREATE TABLE risk_rules (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_name                   TEXT NOT NULL UNIQUE,
    action                      TEXT NOT NULL CHECK (action IN ('hold', 'flag_allow')),
    velocity_max_count          INT,
    velocity_window_minutes     INT,
    amount_spike_multiplier     NUMERIC,
    new_payee_min_amount_paise  BIGINT
);

INSERT INTO risk_rules (rule_name, action, velocity_max_count, velocity_window_minutes) VALUES
    ('velocity', 'hold', 5, 10);
INSERT INTO risk_rules (rule_name, action, amount_spike_multiplier) VALUES
    ('amount_spike', 'hold', 5);
INSERT INTO risk_rules (rule_name, action, new_payee_min_amount_paise) VALUES
    ('new_payee_large_amount', 'flag_allow', 1000000); -- Rs 10,000

-- One row per transfer that a rule flagged. label/explanation start NULL and are filled in by
-- the nightly job's LLM explanation step (Part B); rule_name/created_at are set immediately by
-- the rule itself, at transfer time, before any LLM is involved.
CREATE TABLE risk_flags (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id  BIGINT NOT NULL REFERENCES transfers (id),
    rule_name    TEXT NOT NULL,
    label        TEXT CHECK (label IN ('likely_ok', 'review', 'likely_fraud')),
    explanation  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_risk_flags_transfer_id ON risk_flags (transfer_id);
