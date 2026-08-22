package com.example.fintech.service;

import com.example.fintech.model.Direction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

/**
 * Maintains a single, globally-ordered SHA-256 hash chain across every
 * ledger_entries row. Each entry's hash covers its own data plus the
 * previous entry's hash, so altering or deleting any historical row
 * changes that row's hash and every hash chained after it - the same
 * tamper-evidence property blockchains use, applied to a single
 * authoritative database instead of a distributed one. There's one writer
 * (this service, inside one Postgres transaction), so the audit guarantee
 * comes from serializing writes through ledger_chain_state rather than
 * needing distributed consensus on ordering.
 *
 * Hash inputs are taken as discrete primitives rather than a LedgerEntry
 * object so this is unit-testable without constructing a full entity
 * graph (Account, LedgerTransaction, etc).
 */
@Service
@RequiredArgsConstructor
public class HashChainService {

    private final EntityManager entityManager;

    public String lockAndGetLastHash() {
        return (String) entityManager
                .createNativeQuery("SELECT last_hash FROM ledger_chain_state WHERE id = 1 FOR UPDATE")
                .getSingleResult();
    }

    public void advanceChain(String newHash) {
        entityManager
                .createNativeQuery("UPDATE ledger_chain_state SET last_hash = :hash WHERE id = 1")
                .setParameter("hash", newHash)
                .executeUpdate();
    }

    public String computeHash(String prevHash, Long transactionId, String accountRef,
                               Direction direction, BigDecimal amount, LocalDateTime createdAt) {
        String payload = prevHash + "|" + transactionId + "|" + accountRef + "|"
                + direction + "|" + amount.toPlainString() + "|" + createdAt;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
