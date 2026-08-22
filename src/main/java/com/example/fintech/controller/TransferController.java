package com.example.fintech.controller;

import com.example.fintech.dto.TransferRequest;
import com.example.fintech.dto.TransferResponse;
import com.example.fintech.model.LedgerEntry;
import com.example.fintech.model.LedgerTransaction;
import com.example.fintech.repository.LedgerEntryRepository;
import com.example.fintech.repository.LedgerTransactionRepository;
import com.example.fintech.service.IdempotencyService;
import com.example.fintech.service.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class TransferController {

    private static final Logger log = LoggerFactory.getLogger(TransferController.class);

    private final TransferService transferService;
    private final IdempotencyService idempotencyService;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    @PostMapping
    public ResponseEntity<?> createTransfer(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        try {
            log.info("Posting transaction, idempotency key {}", idempotencyKey);
            TransferResponse response = transferService.postTransaction(request, idempotencyKey);
            idempotencyService.updateStatus(idempotencyKey, "COMPLETED");
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (DataIntegrityViolationException e) {
            // Redis said this key was free (its TTL likely expired since the
            // original request, or Redis lost state), but Postgres' unique
            // constraint proves it was already processed. Replay the
            // original result instead of erroring, and don't free the
            // Redis lock for this outcome.
            log.warn("Idempotency key {} already exists; replaying original transaction", idempotencyKey);
            Optional<LedgerTransaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                idempotencyService.updateStatus(idempotencyKey, "COMPLETED");
                return ResponseEntity.ok(toResponse(existing.get()));
            }
            idempotencyService.removeKey(idempotencyKey);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Duplicate idempotency key detected, but original transaction could not be retrieved.");

        } catch (NoSuchElementException e) {
            idempotencyService.removeKey(idempotencyKey);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());

        } catch (IllegalArgumentException e) {
            // Validation failure (unbalanced entries, insufficient funds) -
            // not a duplicate, not a real failure, so free the lock and let
            // the client correct and retry with the same key.
            idempotencyService.removeKey(idempotencyKey);
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(e.getMessage());

        } catch (Exception e) {
            log.error("Transaction failed for idempotency key {}", idempotencyKey, e);
            idempotencyService.removeKey(idempotencyKey);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Transaction failed. Safety lock released.");
        }
    }

    private TransferResponse toResponse(LedgerTransaction transaction) {
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransaction_IdOrderByIdAsc(transaction.getId());
        List<TransferResponse.EntryView> views = entries.stream()
                .map(e -> TransferResponse.EntryView.builder()
                        .entryId(e.getId())
                        .accountRef(e.getAccount().getAccountRef())
                        .direction(e.getDirection())
                        .amount(e.getAmount())
                        .entryHash(e.getEntryHash())
                        .build())
                .toList();
        return TransferResponse.builder()
                .transactionId(transaction.getId())
                .idempotencyKey(transaction.getIdempotencyKey())
                .description(transaction.getDescription())
                .createdAt(transaction.getCreatedAt())
                .entries(views)
                .build();
    }
}
