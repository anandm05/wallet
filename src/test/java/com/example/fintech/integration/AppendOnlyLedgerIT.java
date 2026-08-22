package com.example.fintech.integration;

import com.example.fintech.repository.AccountRepository;
import com.example.fintech.model.Account;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

class AppendOnlyLedgerIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AccountRepository accountRepository;

    @Test
    void updatingALedgerEntryIsBlockedByTheDbTrigger() {
        Account account = new Account();
        account.setAccountRef("audit_test_acc_" + System.nanoTime());
        account.setCurrency("USD");
        account.setBalance(java.math.BigDecimal.ZERO);
        account.setCreatedAt(LocalDateTime.now());
        account = accountRepository.save(account);

        Long txId = jdbc.queryForObject(
                "insert into ledger_transactions(idempotency_key, description, created_at) values (?, ?, now()) returning id",
                Long.class, "audit-test-" + System.nanoTime(), "seed row for trigger test");

        String dummyHash = "0".repeat(64);
        jdbc.update("insert into ledger_entries(transaction_id, account_id, direction, amount, created_at, prev_hash, entry_hash) " +
                        "values (?, ?, 'CREDIT', 1.00, now(), ?, ?)",
                txId, account.getId(), dummyHash, dummyHash);

        // trg_ledger_entries_immutable must reject this - ledger_entries is
        // append-only by design, not just by convention.
        assertThatThrownBy(() ->
                jdbc.update("update ledger_entries set amount = 999.00 where transaction_id = ?", txId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() ->
                jdbc.update("delete from ledger_entries where transaction_id = ?", txId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }
}
