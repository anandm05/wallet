package com.example.fintech.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity @Table(name = "accounts") @Getter @Setter @NoArgsConstructor
public class Account {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "account_ref", nullable = false, unique = true) private String accountRef;
    // V3 created this column CHAR(3) (fixed-width ISO 4217 code) - JdbcTypeCode
    // is required, not just columnDefinition, because Hibernate's schema
    // *validator* derives the expected JDBC type from the Java type alone.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, columnDefinition = "char(3)") private String currency = "USD";
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal balance = BigDecimal.ZERO;
    @Version private Long version;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
}
