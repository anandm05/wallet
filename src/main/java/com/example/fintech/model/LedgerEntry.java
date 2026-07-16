package com.example.fintech.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ledger_entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String type; // DEBIT or CREDIT

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
