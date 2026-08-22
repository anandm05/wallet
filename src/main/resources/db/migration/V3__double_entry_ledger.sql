-- ============================================================
-- Double-entry ledger redesign
-- ============================================================

CREATE TABLE IF NOT EXISTS accounts (
    id BIGSERIAL PRIMARY KEY,
    account_ref VARCHAR(255) NOT NULL UNIQUE,
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ledger_transactions (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

INSERT INTO accounts (account_ref, currency, balance)
SELECT DISTINCT account_id, 'USD', 0 FROM ledger_entries
ON CONFLICT (account_ref) DO NOTHING;

ALTER TABLE ledger_entries RENAME TO ledger_entries_legacy;

CREATE TABLE ledger_entries (
    id BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT NOT NULL REFERENCES ledger_transactions(id),
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    direction VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    prev_hash CHAR(64),
    entry_hash CHAR(64) NOT NULL
);

CREATE INDEX idx_ledger_entries_account_id ON ledger_entries(account_id);
CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries(transaction_id);

CREATE TABLE ledger_chain_state (
    id SMALLINT PRIMARY KEY DEFAULT 1,
    last_hash CHAR(64) NOT NULL,
    CONSTRAINT single_row CHECK (id = 1)
);
INSERT INTO ledger_chain_state (id, last_hash)
VALUES (1, repeat('0', 64));

-- Ledger entries are an audit record: enforce append-only at the
-- database level, not just by convention in application code.
CREATE OR REPLACE FUNCTION prevent_ledger_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_mutation();
