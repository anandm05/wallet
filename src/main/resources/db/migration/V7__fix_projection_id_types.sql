-- V6 originally typed transaction references as UUID. Transaction ids are
-- BIGSERIAL/Long (see V3), so align these two tables with that rather than
-- editing V6 directly — once a migration has a number, it's immutable;
-- schema corrections always go forward in a new file, never backward into
-- an existing one (Flyway checksums applied migrations and refuses to
-- start if one changes underneath it).
ALTER TABLE account_balance_projection
    ALTER COLUMN last_transaction_id TYPE BIGINT USING NULL;

ALTER TABLE processed_events
    ALTER COLUMN transaction_id TYPE BIGINT USING NULL;
