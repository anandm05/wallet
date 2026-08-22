package com.example.fintech.dto;

import java.math.BigDecimal;
import java.util.List;

// Outbox/Kafka payload shape - kept separate from TransferResponse so the
// wire event contract can evolve independently of the HTTP API contract.
public record TransactionEvent(Long transactionId, List<Leg> legs) {
    public record Leg(String accountRef, BigDecimal amount, String direction) { }
}
