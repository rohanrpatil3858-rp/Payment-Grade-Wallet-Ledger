-- Milestone 5: held transfers have no ledger entries yet, so we need another way to know who
-- the transfer is between in order to release it later.
ALTER TABLE transfers ADD COLUMN sender_account_id BIGINT REFERENCES accounts (id);
ALTER TABLE transfers ADD COLUMN recipient_account_id BIGINT REFERENCES accounts (id);
