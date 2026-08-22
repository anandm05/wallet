# wallet

A double-entry ledger service (Spring Boot 4.1.0 / Java 17) with a SHA-256
hash-chained audit trail, transactional-outbox delivery to Kafka, and
Redis-backed request idempotency. Built as a portfolio project for
backend/fintech interviews.

## Architecture

- **`TransferController` → `TransferService`** — the only write path
  (`POST /api/v1/transfers`). Validates a balanced multi-leg transaction,
  locks every touched account (sorted by ref, deadlock-safe), applies
  balances, extends the hash chain, writes an outbox row — all in one
  `@Transactional` method.
- **`IdempotencyInterceptor`/`IdempotencyService`** — Redis lock on
  `X-Idempotency-Key` (fast path). The real source of truth is the
  Postgres unique constraint on `ledger_transactions.idempotency_key`,
  not the Redis check.
- **`OutboxPublisher`** — `@Scheduled` poll of unpublished `outbox_events`
  rows (`FOR UPDATE SKIP LOCKED`), publishes to Kafka topic
  `wallet-transactions`, exponential backoff to 5 attempts.
- **`BalanceProjectionConsumer`** — Kafka consumer, group
  `wallet-balance-projection`, maintains `account_balance_projection` (a
  read-optimized, eventually-consistent balance cache).
- **`ChainVerificationService`** — `GET /api/v1/audit/verify-chain`
  replays every ledger entry and recomputes its hash.
- **Flyway `V1`–`V7`** — versioned schema, including a mid-series
  single-entry → double-entry redesign (V3). Migrations are immutable once
  numbered; corrections go forward in a new file (see V7's own comment).
- **DB-level guarantees**: `ledger_entries` is append-only via trigger
  (blocks `UPDATE`/`DELETE` even for a superuser); a `DEFERRABLE`
  constraint trigger rejects any transaction whose legs don't sum to zero.

## Build & run

Local (no infra):
```
mvn compile        # main sources
mvn test-compile   # needs spring-boot-resttestclient - see gotchas below
```

Full stack, via Docker Compose (Postgres + Redis + Kafka + app, tuned for
a 1GB-RAM host):
```
docker compose build app   # ~30-60s warm-cache; see Dockerfile notes below
docker compose up -d app
```
`.env` (gitignored) must hold `POSTGRES_PASSWORD` — the compose file
fails fast (`:?`) without it.

## Boot 4.1 gotchas (all found the hard way — none of these error clearly)

Spring Boot 4.1 split several things that used to be automatic out of
`spring-boot-autoconfigure` into their own modules. Missing one doesn't
error at compile time — it just silently doesn't wire up, and the failure
surfaces somewhere unrelated much later:

- **`TestRestTemplate`** moved to `org.springframework.boot.resttestclient`
  in a new `spring-boot-resttestclient` module — `spring-boot-starter-test`
  no longer pulls it in.
- **Flyway** needs `spring-boot-starter-flyway` (not just
  `org.flywaydb:flyway-core`) — without it, Flyway never runs at all, with
  zero log output, and Hibernate's schema validator fails on a
  "missing table" that Flyway just never got the chance to create.
- **Jackson** needs `spring-boot-starter-jackson` for a
  `com.fasterxml.jackson.databind.ObjectMapper` bean to exist at all —
  Boot 4.1's own auto-configured bean is the new Jackson 3
  `tools.jackson.databind.json.JsonMapper` type, a different class
  entirely.
- **Kafka**: Boot's auto-configured `KafkaTemplate`/`ConsumerFactory` are
  generically `<Object, Object>`, which doesn't satisfy this app's
  `KafkaTemplate<String, String>` / `@KafkaListener` needs. See
  `KafkaConfig` for the explicit beans, including
  `kafkaListenerContainerFactory` by name (Spring's default lookup) and
  `@EnableKafka` — without the latter, `@KafkaListener` methods are never
  registered, with **no error at all**; the only symptom is that no
  consumer group ever appears in Kafka.
- **JDK 25 as the build JDK silently breaks Lombok** codegen (methods
  like `getId()` just don't materialize, "cannot find symbol" at a call
  site). Build with JDK 17, matching `pom.xml`'s declared `java.version`.
- **`@JdbcTypeCode`** is required alongside `@Column(columnDefinition=...)`
  for CHAR/jsonb columns — `columnDefinition` only affects DDL generation.
  Hibernate's schema *validator* and its JDBC *binding* both infer the
  type from the plain Java type otherwise (`String` → `VARCHAR`), so a
  `CHAR(64)` or `jsonb` column needs `@JdbcTypeCode(SqlTypes.CHAR)` /
  `@JdbcTypeCode(SqlTypes.JSON)` explicitly (see `Account.currency`,
  `LedgerEntry.prevHash`/`entryHash`, `OutboxEvent.payload`).
- **Hash-chain timestamps must be truncated to microseconds**
  (`LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)`) before hashing —
  Postgres `TIMESTAMP` only stores microsecond precision, so hashing an
  untruncated nanosecond-precision value makes the hash unreproducible
  from what's actually persisted. Without this, every `verify-chain`
  check is a guaranteed false positive.

## Dockerfile caching

`~/.m2` is cached via a BuildKit `--mount=type=cache` mount, but only
across a *separate* `COPY pom.xml` + `RUN dependency:go-offline` step —
the actual `COPY src` + `mvn package` step is deliberately its own layer
so a code change always triggers a real recompile. Never merge these two
steps; that was the original bug this pattern replaced (cached the
compile output itself, not just the dependencies). Requires
`docker-buildx` installed on the build host (`apt-get install
docker-buildx` on Ubuntu — it isn't pulled in by the base `docker.io`
package).

## Deployment

- EC2 instance `i-0cc0601d0e320c3d8` (us-east-1c), t3.micro, Ubuntu 24.04,
  **1GB RAM / 30GB disk — free tier, hard ceiling, cannot be resized.**
- A 2GB swapfile is required and already set up
  (`infra/setup-swap.sh`, idempotent) — without it this combination of
  services OOM-kills under any real load.
- `docker-compose.yml` resource-caps every service for the 1GB host;
  Kafka runs single-node KRaft (no Zookeeper) with capped heap and short
  log retention (48h) so it never becomes a long-term storage sink.
- Postgres/Redis/Kafka are **not** published to the host at all — only
  the app's 8080 is, and only the app talks to them, over the internal
  compose network.

## Security

- **Auth**: OAuth2/JWT via AWS Cognito (chosen over self-hosted Keycloak
  specifically because Keycloak's own JVM would compete for RAM with
  Postgres/Redis/Kafka/the app on the same 1GB box). User pool
  `wallet-users` (`us-east-1_QWF7MMCpE`). Spring Security is a resource
  server (`SecurityConfig`) — every `/api/v1/**` request needs a valid
  JWT; issuer URL is in `application.yml`, overridable via
  `COGNITO_ISSUER_URI`.
  - UI app client (`wallet-ui`): Authorization Code + PKCE, no secret —
    for the planned frontend. Callback URL is currently a
    `localhost:3000` placeholder; update once the UI has a real deployed
    origin, and add that origin to `SecurityConfig`'s CORS allowlist too
    (currently `http://localhost:*` only).
  - M2M app client (`wallet-api-testing`): client-credentials flow, for
    direct API testing without a UI. Credentials in `.env`
    (`COGNITO_M2M_CLIENT_ID`/`_SECRET`), token endpoint at
    `https://wallet-ledger-723929011414.auth.us-east-1.amazoncognito.com/oauth2/token`.
- **Network**: `fintech-security-group` (`sg-0751ef115d9eb66f4`) allows
  only 22 (SSH) and 8080, both restricted to a specific IP —
  **revisit once the UI exists and needs to reach the API from wherever
  it's hosted**; the likely answer then is "open to the internet,
  Cognito-gated" rather than an IP allowlist.
- **Not done yet**: TLS (app still serves plain HTTP — needed before any
  public/production use); IAM hygiene (AWS CLI access is currently the
  root user); secrets are a plain `.env` file, not Secrets Manager/SSM.

## Git

Real GitHub remote: `origin` → `anandm05/wallet`, branch `main`. Commits
are made locally as work lands — **not pushed automatically**; push only
when explicitly asked.
