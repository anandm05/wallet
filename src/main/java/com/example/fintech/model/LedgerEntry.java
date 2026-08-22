package com.example.fintech.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.LocalDateTime;

// A single append-only posting against one account. Always created in
// balanced pairs (or groups) via LedgerTransaction. Mutation is blocked at
// the DB level (trg_ledger_entries_immutable) and balance is enforced at
// the DB level too (trg_ledger_entries_balanced, V5) - not just in code.
@Entity @Table(name = "ledger_entries") @Getter @Setter @NoArgsConstructor
public class LedgerEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "transaction_id", nullable = false)
    private LedgerTransaction transaction;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 6)
    private Direction direction;

    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;

    // Hash chain: entry_hash = SHA-256(prevHash|transactionId|accountRef|direction|amount|createdAt).
    // Tampering with any historical row breaks its hash and every hash after it.
    // Both columns are CHAR(64) in V3 - JdbcTypeCode is required for Hibernate's
    // schema validator to accept that instead of expecting VARCHAR(64).
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "prev_hash", columnDefinition = "char(64)") private String prevHash;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "entry_hash", nullable = false, columnDefinition = "char(64)") private String entryHash;
}
