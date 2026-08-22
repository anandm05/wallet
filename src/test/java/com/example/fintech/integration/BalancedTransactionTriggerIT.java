package com.example.fintech.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BalancedTransactionTriggerIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void addingAnUnmatchedEntryToAnExistingTransactionIsRejectedAtCommit() {
        Long accountId = jdbc.queryForObject(
                "insert into accounts(account_ref, currency, balance, created_at) values (?, 'USD', 1000, now()) returning id",
                Long.class, "trigger_test_acc_" + System.nanoTime());

        Long txId = jdbc.queryForObject(
                "insert into ledger_transactions(idempotency_key, description, created_at) values (?, ?, now()) returning id",
                Long.class, "balance-trigger-test-" + System.nanoTime(), "balanced pair");

        String h1 = "1".repeat(64);
        String h2 = "2".repeat(64);
        String genesis = "0".repeat(64);

        // A genuinely balanced pair under this transaction_id - this must
        // succeed, establishing that the trigger doesn't false-positive on
        // valid data.
        jdbc.update("insert into ledger_entries(transaction_id, account_id, direction, amount, created_at, prev_hash, entry_hash) " +
                "values (?, ?, 'DEBIT', 50.00, now(), ?, ?)", txId, accountId, genesis, h1);
        jdbc.update("insert into ledger_entries(transaction_id, account_id, direction, amount, created_at, prev_hash, entry_hash) " +
                "values (?, ?, 'CREDIT', 50.00, now(), ?, ?)", txId, accountId, h1, h2);

        // Now add a THIRD, unmatched entry under the SAME transaction_id -
        // this makes the transaction's aggregate debits != credits, which
        // the deferred constraint trigger from V5 must reject at commit.
        // Each jdbc.update() here runs in its own auto-commit transaction,
        // so the violation surfaces synchronously from this call.
        String h3 = "3".repeat(64);
        assertThatThrownBy(() ->
                jdbc.update("insert into ledger_entries(transaction_id, account_id, direction, amount, created_at, prev_hash, entry_hash) " +
                        "values (?, ?, 'DEBIT', 25.00, now(), ?, ?)", txId, accountId, h2, h3))
                .isInstanceOf(DataAccessException.class);
    }
}
