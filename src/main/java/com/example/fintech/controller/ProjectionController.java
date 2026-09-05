package com.example.fintech.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Slf4j
public class ProjectionController {

    private final JdbcTemplate jdbc;

    @GetMapping("/{accountRef}/projection")
    public ResponseEntity<?> get(@PathVariable String accountRef) {
        return jdbc.query(
                "select balance, last_transaction_id, updated_at from account_balance_projection where account_ref = ?",
                rs -> {
                    if (!rs.next()) {
                        log.debug("No balance projection yet for account {}", accountRef);
                        return ResponseEntity.notFound().build();
                    }
                    return ResponseEntity.ok(Map.of(
                            "accountRef", accountRef,
                            "balance", rs.getBigDecimal("balance"),
                            "lastTransactionId", rs.getObject("last_transaction_id").toString(),
                            "updatedAt", rs.getTimestamp("updated_at").toInstant().toString()));
                },
                accountRef);
    }
}
