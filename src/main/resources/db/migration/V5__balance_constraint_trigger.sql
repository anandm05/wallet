CREATE OR REPLACE FUNCTION check_ledger_transaction_balance() RETURNS trigger AS $$
BEGIN
    IF NEW.transaction_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM ledger_entries WHERE transaction_id = NEW.transaction_id
        GROUP BY transaction_id
        HAVING COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount WHEN direction = 'DEBIT' THEN -amount ELSE 0 END), 0) <> 0
    ) THEN
        RAISE EXCEPTION 'ledger transaction % is not balanced', NEW.transaction_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balanced
AFTER INSERT OR UPDATE OF amount, direction, transaction_id ON ledger_entries
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_ledger_transaction_balance();
