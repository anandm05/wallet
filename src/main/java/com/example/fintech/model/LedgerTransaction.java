package com.example.fintech.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity @Table(name = "ledger_transactions") @Getter @Setter @NoArgsConstructor
public class LedgerTransaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "idempotency_key", nullable = false, unique = true) private String idempotencyKey;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(length = 500) private String description;
}
