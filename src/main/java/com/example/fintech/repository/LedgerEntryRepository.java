package com.example.fintech.repository;

import com.example.fintech.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    List<LedgerEntry> findByAccount_AccountRefOrderByCreatedAtAsc(String accountRef);
    List<LedgerEntry> findAllByOrderByIdAsc();
    List<LedgerEntry> findByTransaction_IdOrderByIdAsc(Long transactionId);
}
