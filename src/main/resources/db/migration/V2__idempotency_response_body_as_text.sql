-- Milestone 3: idempotency_keys.response_body must preserve the exact response bytes for
-- replay, but jsonb reformats whitespace (e.g. adds a space after ':'), breaking byte-for-byte
-- replay. Store it as plain text instead; the application is responsible for writing valid JSON.
ALTER TABLE idempotency_keys ALTER COLUMN response_body TYPE TEXT USING response_body::text;
