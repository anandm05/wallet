package com.example.fintech.service;

import com.example.fintech.model.Direction;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HashChainServiceTest {

    // EntityManager isn't exercised by computeHash() - only by
    // lockAndGetLastHash()/advanceChain(), which need a real DB connection
    // and are covered by the integration tests instead. A mock is enough
    // here since this class only tests the pure hashing function.
    private final HashChainService service = new HashChainService(mock(EntityManager.class));

    @Test
    void sameInputsProduceSameHash() {
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        String h1 = service.computeHash("prev", 1L, "acc_1", Direction.DEBIT, new BigDecimal("10.00"), now);
        String h2 = service.computeHash("prev", 1L, "acc_1", Direction.DEBIT, new BigDecimal("10.00"), now);
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void differentAmountProducesDifferentHash() {
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        String h1 = service.computeHash("prev", 1L, "acc_1", Direction.DEBIT, new BigDecimal("10.00"), now);
        String h2 = service.computeHash("prev", 1L, "acc_1", Direction.DEBIT, new BigDecimal("10.01"), now);
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void differentDirectionProducesDifferentHash() {
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        String debit = service.computeHash("prev", 1L, "acc_1", Direction.DEBIT, new BigDecimal("10.00"), now);
        String credit = service.computeHash("prev", 1L, "acc_1", Direction.CREDIT, new BigDecimal("10.00"), now);
        assertThat(debit).isNotEqualTo(credit);
    }

    @Test
    void chainingFeedsPreviousHashForwardCorrectly() {
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        String genesis = "0".repeat(64);

        String entry1Hash = service.computeHash(genesis, 1L, "acc_1", Direction.DEBIT, new BigDecimal("10.00"), now);
        String entry2Hash = service.computeHash(entry1Hash, 1L, "acc_2", Direction.CREDIT, new BigDecimal("10.00"), now);

        // Recomputing entry 2's hash with the WRONG previous hash (as if
        // entry 1 had been tampered with) must produce a different result -
        // this is the actual tamper-evidence property being tested.
        String recomputedWithWrongPrev = service.computeHash("tampered", 1L, "acc_2", Direction.CREDIT, new BigDecimal("10.00"), now);
        assertThat(recomputedWithWrongPrev).isNotEqualTo(entry2Hash);
    }

    @Test
    void hashIsA64CharacterHexString() {
        String hash = service.computeHash("prev", 1L, "acc_1", Direction.DEBIT, BigDecimal.TEN, LocalDateTime.now());
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }
}
