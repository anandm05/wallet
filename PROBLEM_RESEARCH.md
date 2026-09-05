# What this project actually solves

This maps every non-trivial piece of this codebase to the real, named industry
problem it addresses — with sources — so the project's story stops being "I
built a wallet app" and becomes a list of specific, verifiable engineering
decisions. Written for: the README, outreach messages, and interview answers.

Grounded in the actual code (not the other way around) — file/class names
below are real, checked against `src/main/java/com/example/fintech/` and
`src/main/resources/db/migration/` on 2026-09-04.

---

## 1. Duplicate charges from network retries

**Industry problem:** A client sends a transfer request, the network times
out before the response arrives, the client doesn't know if it succeeded, and
retries. Without protection this creates a second, real transaction — a
duplicate charge. This is arguably the single most common correctness bug in
payment systems, common enough that Stripe's idempotency-key pattern became
the de facto industry standard other processors (PayPal, Adyen) converged on.

**What the code does:** `IdempotencyInterceptor` + `IdempotencyService` — but
not a single-layer solution. Redis holds a fast-path lock on the idempotency
key; `TransferController` has an explicit comment for the case where Redis
says the key is free but shouldn't be (TTL expired, Redis lost state):
Postgres' own `uq_ledger_idempotency_key` UNIQUE constraint
(`V2__add_idempotency_key.sql`) is the real backstop, not the cache. That's a
deliberate "don't trust the cache for correctness" decision, not an oversight.

**Why it matters to an employer:** this is the exact failure mode fintech
interviewers ask about ("how do you stop a retry from double-charging a
customer") — and the answer here isn't a guess, it's a working two-layer
implementation with the reasoning documented in the code itself.

---

## 2. The dual-write problem (DB write + event publish can't be one transaction)

**Industry problem:** Updating a database and publishing an event to Kafka
are two separate systems with no shared transaction. The DB write can succeed
while the Kafka publish fails (or vice versa), leaving the ledger and every
downstream consumer (balance projections, notifications) silently
out of sync — a standard, widely-documented distributed-systems pain point.

**What the code does:** `OutboxEvent` + `OutboxPublisher`
(`V4__outbox_events.sql`) — the transactional outbox pattern. The ledger
write and the "intent to publish" are committed in the same DB transaction;
a background publisher drains the outbox table into Kafka afterward. If the
publish step fails, the source of truth (the DB) was never inconsistent —
only the publish gets retried, not the ledger write.

**Why it matters:** this is the standard, correct fix for exactly the kind
of silent data-drift bug that's hard to reproduce and worse to debug in
production — the kind of thing a company only wants a candidate who's
actually built it before, not just read about it.

---

## 3. Ledger correctness at the database layer, not just the application layer

**Industry problem:** Fintech products that touch deposits, withdrawals,
fees, or settlements need every transaction to balance (debits = credits) —
without that, a single-entry-style system becomes a source of silent
balance drift and reconciliation headaches, and fails audit. Double-entry
is described by fintech infrastructure vendors as effectively non-optional
once money moves through the system.

**What the code does:** `LedgerEntry` / `LedgerTransaction` / `Direction`
model double-entry directly, but the invariant isn't just enforced in Java —
`V5__balance_constraint_trigger.sql` adds a **deferred Postgres constraint
trigger** that raises an exception if any transaction's entries don't sum to
zero, checked at commit time. So even a bug in the application code, a
different code path, or a direct DB write can't silently produce an
unbalanced ledger — the database itself refuses it.

**Why it matters:** most take-home/portfolio ledger projects enforce balance
only in application code, which is the layer most likely to have the bug in
the first place. Enforcing it at the DB layer too is the kind of
defense-in-depth an actual fintech backend team would expect, not something
typically found in a demo project.

---

## 4. Tamper-evidence for financial audit records

**Industry problem:** Financial audit logs are subject to real regulatory
requirements, not just good practice — SEC Rule 17a-4 requires non-rewriteable,
non-erasable record storage; PCI DSS v4.0 Requirement 10.3 requires
protecting audit logs from destruction and unauthorized modification, which
in practice means cryptographic integrity controls. SOX audit requirements
are frequently satisfied the same way: hash-chaining so any modification to
a past record is detectable.

**What the code does:** `HashChainService` + `ChainVerificationService`,
exposed via `AuditController` — each audit record's hash incorporates the
previous record's hash, so altering any past entry breaks every hash after
it. `ChainVerificationService` is the explicit "prove nothing was tampered
with" verification pass, not just a write-time hash.

**Why it matters:** this is the same mechanism (append-only + hash chaining
+ a verify step) that compliance guides describe as what examiners actually
accept — the project isn't approximating a real compliance control, it's
implementing the specific one.

---

## 5. Fast reads over an append-only ledger (CQRS-style projection)

**Industry problem:** An append-only ledger is the right source of truth for
correctness, but querying "what's this account's current balance" by
replaying every transaction doesn't scale as transaction volume grows — a
standard event-sourcing / CQRS pain point.

**What the code does:** `BalanceProjectionConsumer` reads the Kafka events
published via the outbox and maintains a separate, fast-to-read balance
projection (`V6__balance_projection.sql`, `V7__fix_projection_id_types.sql`
shows this was iterated on, not written once and left) — exposed via
`ProjectionController`. The ledger stays the append-only source of truth;
the projection is a derived, disposable read model that could be rebuilt
from the event log if it ever drifted.

**Why it matters:** this is the read side of the same pattern used at real
payment-system scale (event log + materialized projections) — a company
asking "how would this scale past a few thousand transactions" gets an
answer that's already implemented, not hypothetical.

---

## 6. Tests that catch what mocks can't

**Industry problem:** Tests against mocked DBs/brokers routinely pass while
the same code fails against the real thing — differing SQL dialect quirks,
transaction/locking behavior, or Kafka consumer-group semantics a mock
doesn't reproduce. A common, well-known gap between "tests are green" and
"it actually works."

**What the code does:** Testcontainers for both Postgres and Kafka
(`pom.xml`) — integration tests run against real, ephemeral instances of the
actual database and broker being used in production, not simulations of them.

**Why it matters:** given the correctness-under-failure focus of everything
above (idempotency, outbox, balance triggers), testing against mocks would
have quietly undermined the whole point — this closes that gap.

---

## Summary table

| Code | Industry problem | Standard fix |
|---|---|---|
| `IdempotencyService` + Redis + Postgres UNIQUE | Duplicate charges from retries | Idempotency keys (Stripe-standard pattern) |
| `OutboxEvent` / `OutboxPublisher` | Dual-write problem (DB + Kafka) | Transactional outbox pattern |
| `V5__balance_constraint_trigger.sql` | Ledger drift / unbalanced transactions | DB-enforced double-entry invariant |
| `HashChainService` / `ChainVerificationService` | Tamper-evidence for audit records | Hash-chained, append-only audit log (SEC 17a-4 / PCI DSS 10.3 style) |
| `BalanceProjectionConsumer` | Slow reads over an append-only ledger | CQRS-style materialized projection |
| Testcontainers (Postgres + Kafka) | Mocked tests hiding real integration bugs | Integration tests against real DB/broker |

---

## Sources

- [Preventing Duplicate Payments with Idempotency Keys — Stripe, PayPal, Adyen](https://medium.com/@sahintalha1/the-way-psps-such-as-paypal-stripe-and-adyen-prevent-duplicate-payment-idempotency-keys-615845c185bf)
- [How Stripe Prevents Double Payments With Idempotency Keys](https://singhajit.com/how-stripe-prevents-double-payment/)
- [The Outbox Pattern: Solving the Dual Write Problem in Microservices](https://azizollahi.medium.com/the-outbox-pattern-solving-the-dual-write-problem-in-microservices-0d2181c0d148)
- [Designing Event-Driven Microservices — The Transactional Outbox Pattern (Confluent)](https://developer.confluent.io/courses/microservices/the-transactional-outbox-pattern/)
- [What Is a Double-Entry Ledger in Fintech? (SDK.finance)](https://sdk.finance/blog/what-is-a-double-entry-ledger-in-fintech/)
- [Why Your Fintech Needs a Double-Entry Ledger](https://medium.com/@payflux/why-your-fintech-needs-a-double-entry-ledger-48314a0e8527)
- [Tamper-Evident Audit Logs: What Examiners Accept (Hash Chains, Signed Exports, WORM)](https://finqub.io/learn/tamper-evident-audit-trail/)
- [Financial Compliance: Audit Trail Guide](https://velt.dev/blog/financial-audit-trail-compliance-guide)
