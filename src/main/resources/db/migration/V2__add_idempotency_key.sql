ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255);

UPDATE ledger_entries SET idempotency_key = 'LEGACY-' || id WHERE idempotency_key IS NULL;

ALTER TABLE ledger_entries ALTER COLUMN idempotency_key SET NOT NULL;
ALTER TABLE ledger_entries ADD CONSTRAINT uq_ledger_idempotency_key UNIQUE (idempotency_key);