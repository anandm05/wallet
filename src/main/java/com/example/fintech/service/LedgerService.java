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
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    public LedgerEntry saveEntry(LedgerEntry requestData, String idempotencyKey) {
        // Create a completely clean entity instance
        LedgerEntry entry = new LedgerEntry();

        // Map payload fields explicitly
        entry.setAccountId(requestData.getAccountId());
        entry.setAmount(requestData.getAmount());
        entry.setType(requestData.getType());

        // Enforce the system-managed keys explicitly
        entry.setIdempotencyKey(idempotencyKey);
        entry.setCreatedAt(LocalDateTime.now());

        // Save the freshly constructed entity
        LedgerEntry savedEntry = ledgerRepository.save(entry);

        // Publish event payload to Kafka cluster
        try {
            log.info("Publishing ledger event to Kafka for account: {}", savedEntry.getAccountId());
            kafkaTemplate.send("wallet-transactions", savedEntry.getAccountId().toString(), savedEntry.toString()).get();
        } catch (Exception e) {
            log.error("Failed to dispatch event stream payload to Kafka", e);
        }

        return savedEntry;
    }
}