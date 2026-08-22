package com.example.fintech.repository;
import com.example.fintech.model.OutboxEvent;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
 @Query(value="select * from outbox_events where published_at is null and next_attempt_at <= now() and publish_attempts < :maxAttempts order by id for update skip locked limit :batchSize", nativeQuery=true)
 List<OutboxEvent> lockReadyEvents(@org.springframework.data.repository.query.Param("batchSize") int batchSize, @org.springframework.data.repository.query.Param("maxAttempts") int maxAttempts);
}
