package com.example.fintech.integration;

import com.example.fintech.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BalancedTransactionTriggerIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransferService transferService;

    @Test
    void addingAnUnmatchedEntryToAnExistingTransactionIsRejectedAtCommit() {
        String accountRef = "trigger_test_acc_" + System.nanoTime();
        Long accountId = jdbc.queryForObject(
                "insert into accounts(account_ref, currency, balance, created_at) values (?, 'USD', 0, now()) returning id",
                Long.class, accountRef);

        // A genuinely balanced pair, posted through the real write path - this
        // must succeed, establishing that the trigger doesn't false-positive
        // on valid data (and keeping the hash chain valid for other tests).
        Long txId = seedTransfer(transferService, accountRef, "balance-trigger-test-");
        String h2 = "2".repeat(64);

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
