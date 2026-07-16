package com.example.fintech.controller;

import com.example.fintech.model.LedgerEntry;
import com.example.fintech.service.IdempotencyService;
import com.example.fintech.service.LedgerService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private static final Logger log = LoggerFactory.getLogger(LedgerController.class);


    private final LedgerService ledgerService;
    private final IdempotencyService idempotencyService;

    @PostMapping
    public ResponseEntity<?> createEntry(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody LedgerEntry request) {

        try {
            log.info("key " + idempotencyKey);
            LedgerEntry savedEntry = ledgerService.saveEntry(request, idempotencyKey);
            idempotencyService.updateStatus(idempotencyKey, "COMPLETED");
            return ResponseEntity.status(HttpStatus.CREATED).body(savedEntry);

        } catch (Exception e) {
            e.printStackTrace();
            idempotencyService.removeKey(idempotencyKey);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Database transaction failed. Safety lock released.");
        }
    }
}