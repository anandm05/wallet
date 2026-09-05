package com.example.fintech.consumer;

import com.example.fintech.dto.TransactionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class BalanceProjectionConsumer {

    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    @KafkaListener(topics = "wallet-transactions", groupId = "wallet-balance-projection")
    @Transactional
    public void consume(String payload) throws Exception {
        TransactionEvent event;
        try {
            event = mapper.readValue(payload, TransactionEvent.class);
        } catch (Exception ex) {
            // Rethrown deliberately, not swallowed: a malformed payload on this
            // topic means something upstream is broken (a producer contract
            // change, a hand-crafted test message) and Spring Kafka's own
            // error handling/retry policy should see it, not silently drop it.
            log.error("Failed to deserialize balance-projection event, payload={}", payload, ex);
            throw ex;
        }

        int claimed = jdbc.update(
                "insert into processed_events(event_type, transaction_id) values (?, ?) on conflict do nothing",
                "TRANSACTION_POSTED", event.transactionId());
        if (claimed == 0) {
            log.debug("Transaction {} already projected, skipping (duplicate delivery)", event.transactionId());
            return;
        }

        for (TransactionEvent.Leg leg : event.legs()) {
            jdbc.update(
                    "insert into account_balance_projection(account_ref,balance,last_transaction_id,updated_at) " +
                            "values (?, ?, ?, now()) on conflict (account_ref) do update set " +
                            "balance=account_balance_projection.balance + excluded.balance, " +
                            "last_transaction_id=excluded.last_transaction_id, updated_at=now()",
                    leg.accountRef(), "CREDIT".equals(leg.direction()) ? leg.amount() : leg.amount().negate(),
                    event.transactionId());
        }
        log.debug("Projected transaction {} ({} legs) into account_balance_projection",
                event.transactionId(), event.legs().size());
    }
}
