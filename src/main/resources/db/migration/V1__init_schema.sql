-- Milestone 1: initial schema for the payment-grade wallet ledger.
-- Tables are created in dependency order: users -> accounts -> transfers -> ledger_entries -> idempotency_keys -> transfer_status_audit_log.

CREATE TABLE users (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        TEXT NOT NULL,
    email       TEXT NOT NULL UNIQUE,
    phone       TEXT NOT NULL UNIQUE,
    kyc_status  TEXT NOT NULL DEFAULT 'pending'
                CHECK (kyc_status IN ('pending', 'verified', 'rejected'))
);

-- One table for both user wallets and system accounts (cash-in, cash-out, fee revenue):
-- ledger_entries only needs a single kind of "account" to reference, regardless of owner.
CREATE TABLE accounts (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT REFERENCES users (id),
    account_type TEXT NOT NULL
                 CHECK (account_type IN ('user_wallet', 'system_cash_in', 'system_cash_out', 'system_fee_revenue')),
    name         TEXT NOT NULL,
    currency     TEXT NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    balance      BIGINT NOT NULL DEFAULT 0,

    -- Wallets must belong to a user; system accounts must not.
    CONSTRAINT accounts_user_id_matches_type CHECK (
        (account_type = 'user_wallet' AND user_id IS NOT NULL) OR
        (account_type <> 'user_wallet' AND user_id IS NULL)
    ),

    -- User wallets can never go negative; system accounts are exempt.
    CONSTRAINT accounts_wallet_balance_non_negative CHECK (
        account_type <> 'user_wallet' OR balance >= 0
    )
);

CREATE INDEX idx_accounts_user_id ON accounts (user_id);

CREATE TABLE transfers (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type                    TEXT NOT NULL
                            CHECK (type IN ('top_up', 'peer_transfer', 'withdrawal', 'fee', 'reversal')),
    status                  TEXT NOT NULL
                            CHECK (status IN ('held', 'completed', 'reversed')),
    amount                  BIGINT NOT NULL CHECK (amount > 0),
    reversal_of_transfer_id BIGINT REFERENCES transfers (id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A reversal transfer must always point back at the transfer it corrects.
    CONSTRAINT transfers_reversal_has_target CHECK (
        type <> 'reversal' OR reversal_of_transfer_id IS NOT NULL
    )
);

CREATE TABLE ledger_entries (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id BIGINT NOT NULL REFERENCES transfers (id),
    account_id  BIGINT NOT NULL REFERENCES accounts (id),
    direction   TEXT NOT NULL CHECK (direction IN ('debit', 'credit')),
    amount      BIGINT NOT NULL CHECK (amount > 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Statement queries read newest-first for one account.
CREATE INDEX idx_ledger_entries_account_statement ON ledger_entries (account_id, created_at DESC, id DESC);
-- Reconciliation checks sum debits/credits per transfer.
CREATE INDEX idx_ledger_entries_transfer_id ON ledger_entries (transfer_id);

-- Ledger entries are append-only: block any UPDATE or DELETE, even from application bugs.
CREATE FUNCTION prevent_ledger_entry_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries rows are immutable: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_no_update
    BEFORE UPDATE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_entry_mutation();

CREATE TRIGGER ledger_entries_no_delete
    BEFORE DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_entry_mutation();

CREATE TABLE idempotency_keys (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id               BIGINT NOT NULL REFERENCES users (id),
    idempotency_key       TEXT NOT NULL,
    request_hash          TEXT NOT NULL,
    status                TEXT NOT NULL DEFAULT 'in_progress'
                          CHECK (status IN ('in_progress', 'completed')),
    response_status_code  INT,
    response_body         JSONB,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Same key from the same user must resolve to exactly one in-flight/completed request.
    CONSTRAINT idempotency_keys_unique_per_user UNIQUE (user_id, idempotency_key)
);

CREATE TABLE transfer_status_audit_log (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id      BIGINT NOT NULL REFERENCES transfers (id),
    old_status       TEXT,
    new_status       TEXT NOT NULL,
    changed_by_actor TEXT NOT NULL,
    changed_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
