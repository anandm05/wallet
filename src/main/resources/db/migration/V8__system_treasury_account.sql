-- Admin "mint/fund" goes through the same double-entry TransferService
-- path as every other transfer - crediting a target account debits this
-- treasury account rather than a special unaudited credit path. Seeded
-- once with a very large balance; ordinary non-negative-balance checks
-- still apply to it, they just never bind in practice.
INSERT INTO accounts (account_ref, currency, balance)
VALUES ('SYSTEM_TREASURY', 'USD', 999999999999.99)
ON CONFLICT (account_ref) DO NOTHING;
