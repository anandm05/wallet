package com.example.fintech.integration;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres + Kafka + Redis via Testcontainers, not H2/mocks/embedded
 * Kafka - so these tests actually exercise the Flyway migrations (V1-V7),
 * the DB triggers (append-only, balance check), and real Kafka delivery
 * to the outbox publisher / balance-projection consumer.
 *
 * One container set is shared across all subclasses via a static
 * initializer + @DynamicPropertySource on this base class, rather than one
 * set per test class, to keep the suite's wall-clock time reasonable.
 *
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("fintech_ledger_test")
                    .withUsername("test_user")
                    .withPassword("test_password");

    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @BeforeAll
    static void startContainers() {
        POSTGRES.start();
        KAFKA.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // Fast poll so outbox-dependent tests don't have to wait a full second.
        registry.add("wallet.outbox.fixed-delay-ms", () -> "200");
    }

    /** Posts SYSTEM_TREASURY -> accountRef for 1.00 via the real service; returns the transaction id. */
    protected static Long seedTransfer(com.example.fintech.service.TransferService transferService,
                                       String accountRef, String keyPrefix) {
        com.example.fintech.dto.TransferRequest request = new com.example.fintech.dto.TransferRequest();
        request.setDescription("test seed");
        request.setEntries(java.util.List.of(
                leg("SYSTEM_TREASURY", com.example.fintech.model.Direction.DEBIT),
                leg(accountRef, com.example.fintech.model.Direction.CREDIT)));
        return transferService.postTransaction(request, keyPrefix + System.nanoTime()).getTransactionId();
    }

    private static com.example.fintech.dto.TransferRequest.Leg leg(String ref, com.example.fintech.model.Direction direction) {
        com.example.fintech.dto.TransferRequest.Leg leg = new com.example.fintech.dto.TransferRequest.Leg();
        leg.setAccountRef(ref);
        leg.setDirection(direction);
        leg.setAmount(new java.math.BigDecimal("1.00"));
        return leg;
    }

    protected TestRestTemplate restTemplate() {
        return new TestRestTemplate();
    }
}
