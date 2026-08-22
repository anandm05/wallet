package com.example.fintech;

import com.example.fintech.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

// This was previously in package com.example.wallet, a sibling of - not an
// ancestor of - com.example.fintech (where @SpringBootApplication actually
// lives). @SpringBootTest with no explicit classes= resolves the
// configuration class by scanning upward from the test's own package, so
// that placement meant this test could never actually find the app context
// and would fail with "Unable to find a @SpringBootConfiguration". Moving
// it here fixes that. It also now extends AbstractIntegrationTest so the
// context actually has a real Postgres/Kafka/Redis to connect to via
// Testcontainers, rather than failing on startup against nothing.
class WalletApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }
}
