CREATE TABLE account_balance_projection (
    account_ref VARCHAR(255) PRIMARY KEY,
    balance NUMERIC(19,2) NOT NULL,
    last_transaction_id UUID NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE processed_events (
    event_type VARCHAR(100) NOT NULL,
    transaction_id UUID NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_type, transaction_id)
);
