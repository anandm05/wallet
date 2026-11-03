package com.example.fintech.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The end-to-end flow the UI drives: create accounts, fund one from the
 * treasury (admin), transfer, then read history, the Kafka-fed projection
 * and the hash-chain check - pinning the JSON shapes the UI depends on.
 */
@AutoConfigureTestRestTemplate
class AccountApiIT extends AbstractIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};

    @Autowired TestRestTemplate rest;
    @MockitoBean JwtDecoder jwtDecoder;

    @BeforeEach
    void stubJwt() {
        when(jwtDecoder.decode(anyString())).thenReturn(Jwt.withTokenValue("admin-token")
                .header("alg", "none").subject("it-admin").claim("scope", "wallet-api/admin").build());
    }

    @Test
    void createFundTransferAndReadBack() throws InterruptedException {
        String alice = "alice_" + System.nanoTime();
        String bob = "bob_" + System.nanoTime();

        assertThat(post("/api/v1/accounts", Map.of("accountRef", alice), null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post("/api/v1/accounts", Map.of("accountRef", bob), null).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map<String, Object>> mint = post("/api/v1/admin/accounts/" + alice + "/mint",
                Map.of("amount", 500.00, "description", "opening balance"), UUID.randomUUID().toString());
        assertThat(mint.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map<String, Object>> transfer = post("/api/v1/transfers", Map.of(
                "description", "rent",
                "entries", List.of(
                        Map.of("accountRef", alice, "direction", "DEBIT", "amount", 120.00),
                        Map.of("accountRef", bob, "direction", "CREDIT", "amount", 120.00))),
                UUID.randomUUID().toString());
        assertThat(transfer.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Object transactionId = transfer.getBody().get("transactionId");

        Map<String, Object> aliceAccount = get("/api/v1/accounts/" + alice, MAP).getBody();
        assertThat(aliceAccount.get("balance").toString()).isEqualTo("380.0");

        List<Map<String, Object>> entries = get("/api/v1/accounts/" + bob + "/entries", LIST).getBody();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0)).containsKeys("id", "transactionId", "direction", "amount", "createdAt", "prevHash", "entryHash");
        assertThat(entries.get(0).get("transactionId")).isEqualTo(transactionId);
        assertThat(entries.get(0).get("direction")).isEqualTo("CREDIT");

        Map<String, Object> chain = get("/api/v1/audit/verify-chain", MAP).getBody();
        assertThat(chain.get("valid")).isEqualTo(true);

        // The projection is eventually consistent (outbox -> Kafka -> consumer).
        ResponseEntity<Map<String, Object>> projection = null;
        for (int i = 0; i < 50; i++) {
            projection = get("/api/v1/accounts/" + bob + "/projection", MAP);
            if (projection.getStatusCode() == HttpStatus.OK) break;
            Thread.sleep(200);
        }
        assertThat(projection.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(projection.getBody().get("balance").toString()).isEqualTo("120.0");
    }

    private ResponseEntity<Map<String, Object>> post(String path, Object body, String idempotencyKey) {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) headers.set("X-Idempotency-Key", idempotencyKey);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private <T> ResponseEntity<T> get(String path, ParameterizedTypeReference<T> type) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders()), type);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("admin-token");
        return headers;
    }
}
