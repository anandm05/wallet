package com.example.fintech.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

@Entity @Table(name = "outbox_events") @Getter @Setter @NoArgsConstructor
public class OutboxEvent {
 @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
 @Column(name="aggregate_type") private String aggregateType;
 @Column(name="aggregate_id") private String aggregateId;
 @Column(name="event_type") private String eventType;
 // The converter handles JsonNode<->String; JdbcTypeCode is what actually
 // makes Hibernate bind that String as jsonb at the JDBC level instead of
 // plain varchar - columnDefinition alone only affects DDL generation.
 @JdbcTypeCode(SqlTypes.JSON)
 @Column(columnDefinition="jsonb") @Convert(converter = JsonNodeConverter.class) private JsonNode payload;
 @Column(name="created_at") private LocalDateTime createdAt;
 @Column(name="published_at") private LocalDateTime publishedAt;
 @Column(name="publish_attempts") private int publishAttempts;
 @Column(name="next_attempt_at") private LocalDateTime nextAttemptAt;
 @Column(name="last_error") private String lastError;
}
