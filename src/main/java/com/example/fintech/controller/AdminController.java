package com.example.fintech.controller;

import com.example.fintech.dto.TransferRequest;
import com.example.fintech.dto.TransferResponse;
import com.example.fintech.model.Direction;
import com.example.fintech.model.LedgerTransaction;
import com.example.fintech.repository.LedgerTransactionRepository;
import com.example.fintech.service.IdempotencyService;
import com.example.fintech.service.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

// Admin-only surface, gated on the wallet-api/admin JWT scope (see
// SecurityConfig). Deliberately thin: mint/fund reuses TransferService
// directly rather than a separate write path, so admin actions carry the
// exact same balance/hash-chain/outbox guarantees as any other transfer.
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private static final String TREASURY_REF = "SYSTEM_TREASURY";

    private final TransferService transferService;
    private final IdempotencyService idempotencyService;
    private final LedgerTransactionRepository transactionRepository;

    @PostMapping("/accounts/{accountRef}/mint")
    public ResponseEntity<?> mint(
            @PathVariable String accountRef,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody MintRequest request) {

        TransferRequest transferRequest = new TransferRequest();
        transferRequest.setDescription(request.getDescription() == null
                ? "Admin mint"
                : "Admin mint: " + request.getDescription());
        transferRequest.setEntries(List.of(
                leg(TREASURY_REF, Direction.DEBIT, request.getAmount()),
                leg(accountRef, Direction.CREDIT, request.getAmount())
        ));

        log.info("Admin mint requested: account={} amount={} idempotencyKey={}",
                accountRef, request.getAmount(), idempotencyKey);
        try {
            TransferResponse response = transferService.postTransaction(transferRequest, idempotencyKey);
            idempotencyService.updateStatus(idempotencyKey, "COMPLETED");
            log.info("Admin mint completed: account={} amount={} transactionId={}",
                    accountRef, request.getAmount(), response.getTransactionId());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (DataIntegrityViolationException e) {
            log.warn("Admin mint rejected: duplicate idempotency key {}", idempotencyKey);
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Duplicate idempotency key.");
        } catch (NoSuchElementException e) {
            log.warn("Admin mint rejected: {}", e.getMessage());
            idempotencyService.removeKey(idempotencyKey);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Admin mint rejected: {}", e.getMessage());
            idempotencyService.removeKey(idempotencyKey);
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(e.getMessage());
        } catch (Exception e) {
            // Matches TransferController's defensive pattern: an admin mint is a
            // real money-movement write, so an unexpected failure here must
            // still release the idempotency lock (otherwise a retry with the
            // same key is permanently stuck) and must never leak a raw 500
            // with no server-side record of what happened.
            log.error("Admin mint failed unexpectedly: account={} amount={} idempotencyKey={}",
                    accountRef, request.getAmount(), idempotencyKey, e);
            idempotencyService.removeKey(idempotencyKey);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Mint failed. Safety lock released.");
        }
    }

    @GetMapping("/transactions")
    public ResponseEntity<List<LedgerTransaction>> allTransactions() {
        return ResponseEntity.ok(transactionRepository.findAll());
    }

    private TransferRequest.Leg leg(String accountRef, Direction direction, BigDecimal amount) {
        TransferRequest.Leg leg = new TransferRequest.Leg();
        leg.setAccountRef(accountRef);
        leg.setDirection(direction);
        leg.setAmount(amount);
        return leg;
    }

    @Getter @Setter
    public static class MintRequest {
        @NotNull @DecimalMin(value = "0.01", message = "amount must be positive")
        private BigDecimal amount;
        private String description;
    }
}
