package com.example.fintech.service;

import com.example.fintech.model.LedgerEntry;
import com.example.fintech.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerRepository ledgerRepository;

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
        return ledgerRepository.save(entry);
    }
}