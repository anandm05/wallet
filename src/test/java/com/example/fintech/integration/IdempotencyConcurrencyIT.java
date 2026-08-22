package com.example.fintech.integration;

import com.example.fintech.model.Account;
import com.example.fintech.repository.AccountRepository;
import com.example.fintech.repository.LedgerEntryRepository;
import com.example.fintech.repository.LedgerTransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyConcurrencyIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate rest;
    @Autowired AccountRepository accountRepository;
    @Autowired LedgerTransactionRepository transactionRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;

    /**
     * This is the test the whole idempotency design exists to satisfy: fire
     * many genuinely concurrent requests carrying the SAME idempotency key
     * and prove exactly one transaction results, regardless of which layer
     * (Redis fast-lock, or the Postgres unique constraint as backstop)
     * ends up being the one that actually blocked each individual duplicate.
     */
    @Test
    void concurrentRequestsWithSameIdempotencyKeyProduceExactlyOneTransaction() throws InterruptedException, ExecutionException, TimeoutException {
        String fromRef = "concurrency_from_" + System.nanoTime();
        String toRef = "concurrency_to_" + System.nanoTime();
        seedAccount(fromRef, new BigDecimal("100000.00"));
        seedAccount(toRef, BigDecimal.ZERO);

        String idempotencyKey = "concurrency-test-" + System.nanoTime();
        Map<String, Object> body = Map.of(
                "description", "concurrency test transfer",
                "entries", List.of(
                        Map.of("accountRef", fromRef, "direction", "DEBIT", "amount", 100.00),
                        Map.of("accountRef", toRef, "direction", "CREDIT", "amount", 100.00)
                ));

        int concurrency = 50;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("X-Idempotency-Key", idempotencyKey);
                return rest.postForEntity("/api/v1/transfers", new HttpEntity<>(body, headers), String.class);
            }));
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown(); // release all 50 threads at once - maximize actual concurrency
        for (Future<ResponseEntity<String>> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // The core assertion: exactly one transaction, exactly two entries,
        // regardless of how many of the 50 requests got a 201 vs a 409 vs a
        // 200-replay - that HTTP-level mix isn't the point, DB state is.
        long txCount = transactionRepository.findByIdempotencyKey(idempotencyKey).isPresent() ? 1 : 0;
        assertThat(txCount).isEqualTo(1);

        Long txId = transactionRepository.findByIdempotencyKey(idempotencyKey).get().getId();
        assertThat(ledgerEntryRepository.findByTransaction_IdOrderByIdAsc(txId)).hasSize(2);

        Account from = accountRepository.findByAccountRef(fromRef).orElseThrow();
        Account to = accountRepository.findByAccountRef(toRef).orElseThrow();
        assertThat(from.getBalance()).isEqualByComparingTo("99900.00"); // moved exactly once, not 50 times
        assertThat(to.getBalance()).isEqualByComparingTo("100.00");
    }

    private void seedAccount(String ref, BigDecimal balance) {
        Account account = new Account();
        account.setAccountRef(ref);
        account.setCurrency("USD");
        account.setBalance(balance);
        account.setCreatedAt(LocalDateTime.now());
        accountRepository.save(account);
    }
}
