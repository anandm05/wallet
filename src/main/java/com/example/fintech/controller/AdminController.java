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

        try {
            TransferResponse response = transferService.postTransaction(transferRequest, idempotencyKey);
            idempotencyService.updateStatus(idempotencyKey, "COMPLETED");
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Duplicate idempotency key.");
        } catch (NoSuchElementException e) {
            idempotencyService.removeKey(idempotencyKey);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            idempotencyService.removeKey(idempotencyKey);
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(e.getMessage());
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
