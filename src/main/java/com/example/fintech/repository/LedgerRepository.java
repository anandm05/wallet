package com.example.fintech.repository;

import com.example.fintech.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {
    // Secondary database-level backup safety check
    boolean existsByIdempotencyKey(String idempotencyKey);
}