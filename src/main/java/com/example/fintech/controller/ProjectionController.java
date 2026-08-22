package com.example.fintech.controller;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.Map;
@RestController @RequestMapping("/api/v1/accounts") @RequiredArgsConstructor
public class ProjectionController {
 private final JdbcTemplate jdbc;
 @GetMapping("/{accountRef}/projection") public ResponseEntity<?> get(@PathVariable String accountRef) {
  return jdbc.query("select balance,last_transaction_id,updated_at from account_balance_projection where account_ref=?", rs -> rs.next() ? ResponseEntity.ok(Map.of("accountRef", accountRef, "balance", rs.getBigDecimal("balance"), "lastTransactionId", rs.getObject("last_transaction_id").toString(), "updatedAt", rs.getTimestamp("updated_at").toInstant().toString())) : ResponseEntity.notFound().build(), accountRef);
 }
}
