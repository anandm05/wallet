package com.example.fintech.service;

import com.example.fintech.model.LedgerEntry;
import com.example.fintech.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerService {

    private final LedgerRepository ledgerRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * Database-only transactional persist method.
     */
    @Transactional
    public LedgerEntry createDbEntry(LedgerEntry requestData, String idempotencyKey) {
        LedgerEntry entry = new LedgerEntry();
        entry.setAccountId(requestData.getAccountId());
        entry.setAmount(requestData.getAmount());
        entry.setType(requestData.getType());
        entry.setIdempotencyKey(idempotencyKey);
        entry.setCreatedAt(LocalDateTime.now());

        return ledgerRepository.save(entry);
    }

    /**
     * Main entry point: Persists record to DB first, then dispatches to Kafka outside the DB transaction.
     */
    public LedgerEntry saveEntry(LedgerEntry requestData, String idempotencyKey) {
        // 1. Commit record to Database first
        LedgerEntry savedEntry = createDbEntry(requestData, idempotencyKey);

        // 2. Publish to Kafka outside @Transactional boundary
        try {
            log.info("Publishing ledger event to Kafka for account: {}", savedEntry.getAccountId());

            // Build a clean JSON string manually to prevent serialization issues
            String jsonPayload = String.format(
                    "{\"id\":%d,\"idempotencyKey\":\"%s\",\"accountId\":\"%s\",\"amount\":%.2f,\"type\":\"%s\",\"createdAt\":\"%s\"}",
                    savedEntry.getId(),
                    savedEntry.getIdempotencyKey(),
                    savedEntry.getAccountId(),
                    savedEntry.getAmount(),
                    savedEntry.getType(),
                    savedEntry.getCreatedAt()
            );

            // Synchronously wait for Kafka acknowledgement
            kafkaTemplate.send("wallet-transactions", savedEntry.getAccountId(), jsonPayload).get();
            log.info("Successfully confirmed message delivery to Kafka!");

        } catch (Exception e) {
            log.error("Failed to dispatch event stream payload to Kafka", e);
        }

        return savedEntry;
    }
}