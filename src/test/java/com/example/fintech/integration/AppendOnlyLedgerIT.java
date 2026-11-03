package com.example.fintech.integration;

import com.example.fintech.repository.AccountRepository;
import com.example.fintech.model.Account;
import com.example.fintech.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

class AppendOnlyLedgerIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    TransferService transferService;

    @Test
    void updatingALedgerEntryIsBlockedByTheDbTrigger() {
        Account account = new Account();
        account.setAccountRef("audit_test_acc_" + System.nanoTime());
        account.setCurrency("USD");
        account.setBalance(java.math.BigDecimal.ZERO);
        account.setCreatedAt(LocalDateTime.now());
        account = accountRepository.save(account);

        // Seed through the real write path (treasury -> account) so the rows
        // carry valid hashes and don't break the global chain other tests verify.
        Long txId = seedTransfer(transferService, account.getAccountRef(), "audit-test-");

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
