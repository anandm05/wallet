package com.example.fintech.service;
import com.example.fintech.model.OutboxEvent;
import com.example.fintech.repository.OutboxEventRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;

@Service @RequiredArgsConstructor @Slf4j
public class OutboxPublisher {
 private final OutboxEventRepository events; private final KafkaTemplate<String, String> kafka; private final com.fasterxml.jackson.databind.ObjectMapper mapper;
 @Value("${wallet.outbox.batch-size:50}") private int batchSize; @Value("${wallet.outbox.max-attempts:5}") private int maxAttempts;
 /** Polling trades bounded delivery latency for simple, portable deployment; use WAL CDC (Debezium) if sub-second delivery is required. */
 @Scheduled(fixedDelayString="${wallet.outbox.fixed-delay-ms:1000}") @Transactional
 public void publishReadyEvents() { for (OutboxEvent event : events.lockReadyEvents(batchSize, maxAttempts)) publish(event); }
 private void publish(OutboxEvent event) { try { kafka.send("wallet-transactions", event.getAggregateId(), mapper.writeValueAsString(event.getPayload())).get(); event.setPublishedAt(LocalDateTime.now()); event.setLastError(null); } catch (Exception ex) { int attempts=event.getPublishAttempts()+1; event.setPublishAttempts(attempts); event.setLastError(ex.getMessage());
   // next_attempt_at avoids hot-looping a broken broker while still allowing independent rows to proceed.
   event.setNextAttemptAt(LocalDateTime.now().plusSeconds(1L << Math.min(attempts, 10))); if (attempts >= maxAttempts) log.error("Outbox event {} exhausted {} publishing attempts: {}", event.getId(), attempts, ex.getMessage()); else log.warn("Outbox event {} failed attempt {}", event.getId(), attempts, ex); } }
}
