package com.example.fintech.controller;

import com.example.fintech.model.Account;
import com.example.fintech.model.LedgerEntry;
import com.example.fintech.repository.AccountRepository;
import com.example.fintech.repository.LedgerEntryRepository;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Slf4j
public class AccountController {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    @GetMapping
    public ResponseEntity<List<Account>> listAccounts() {
        return ResponseEntity.ok(accountRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<?> createAccount(@RequestBody CreateAccountRequest request) {
        try {
            Account account = new Account();
            account.setAccountRef(request.getAccountRef());
            account.setCurrency(request.getCurrency() == null ? "USD" : request.getCurrency());
            account.setBalance(BigDecimal.ZERO);
            account.setCreatedAt(LocalDateTime.now());
            Account saved = accountRepository.save(account);
            log.info("Account created: {}", saved.getAccountRef());
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (DataIntegrityViolationException e) {
            log.warn("Account creation rejected: {} already exists", request.getAccountRef());
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Account already exists: " + request.getAccountRef());
        }
    }

    @GetMapping("/{accountRef}")
    public ResponseEntity<?> getAccount(@PathVariable String accountRef) {
        return accountRepository.findByAccountRef(accountRef)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("No such account: " + accountRef));
    }

    @GetMapping("/{accountRef}/entries")
    public ResponseEntity<?> getHistory(@PathVariable String accountRef) {
        if (accountRepository.findByAccountRef(accountRef).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No such account: " + accountRef);
        }
        List<LedgerEntry> entries = ledgerEntryRepository.findByAccount_AccountRefOrderByCreatedAtAsc(accountRef);
        return ResponseEntity.ok(entries);
    }

    @Getter @Setter
    public static class CreateAccountRequest {
        @NotBlank private String accountRef;
        private String currency;
    }
}
