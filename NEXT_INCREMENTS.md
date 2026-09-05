**Status update, 2026-09-06:** this file (and `PROBLEM_RESEARCH.md`) were
originally written against `~/Desktop/Projects/wallet` — a stale duplicate
directory, not this repo. That's why Increment 0 below says "I still can't
find it locally or under the `anandm05` GitHub account" and "zero auth
code" — both wrong as of today: this repo (`~/Desktop/wallet`) has full
Cognito OAuth2/JWT auth (`SecurityConfig`), is pushed and public at
`github.com/anandm05/wallet`, is deployed and verified live on the EC2
instance, and now has a global exception handler + logging across every
write path. Increment 0's items 1-2 are done; the rest below (reversals,
reconciliation, observability, chaos testing, multi-currency) is still
accurate and still the real next-priority list.

# Roadmap — next increments

Prioritized by what actually strengthens the engineering story (see
`PROBLEM_RESEARCH.md`), not just by what's easiest to add. Grounded in what's
verified true or false in the current code as of 2026-09-04.

## Increment 0 — Fix what's inaccurate, make it visible (blocks everything else)

This isn't a feature increment — it's a correctness pass on the *story*,
same standard the code itself is held to.

1. **Resolve the OAuth2 claim.** Outreach drafts repeatedly cite "OAuth2-based
   auth flows" as part of this project. There is currently zero auth code —
   no Spring Security, no OAuth2 dependency, nothing. Two honest paths:
   - Implement it for real: Spring Security as an OAuth2 resource server,
     protecting `TransferController`/`AccountController` with a bearer token,
     backed by a simple JWT issuer or a test IdP. A few hours of work, and it
     closes a gap in something already being told to real companies.
   - Or stop claiming it — correct `outreach-templates.md` and any drafted
     messages that mention it.
   Recommendation: implement it. It's well-scoped, and it's the one claim in
   your outreach story that's currently false rather than just unverified.
2. **Get the repo actually live and linked.** Confirm where it really is
   (you said it's on git; I still can't find it locally or under the
   `anandm05` GitHub account) — or push it fresh if it turns out it isn't.
3. **README from `PROBLEM_RESEARCH.md`.** That doc is already written for
   this purpose — condense it into a real README with a short architecture
   diagram (ledger → outbox → Kafka → projection).

## Increment 1 — Reversals as first-class ledger operations

**Gap:** no refund/reversal logic exists. A double-entry ledger should never
delete or mutate history — a refund has to be a new, compensating
transaction that references the original.

**Build:** a `POST /transfers/{id}/reverse` endpoint that creates a new
`LedgerTransaction` with inverted legs, linked to the original via a
`reverses_transaction_id` column, subject to the same balance trigger and
idempotency rules as any other transfer.

**Why this order:** it's the most natural "what happens when something goes
wrong" follow-up to the existing transfer flow, and it's the kind of gap an
interviewer probes for specifically because so many take-home ledger
projects only handle the happy path.

## Increment 2 — Reconciliation job

**Gap:** `BalanceProjectionConsumer` maintains a materialized balance, but
nothing currently re-verifies the projection against the ledger (the source
of truth) after the fact.

**Build:** a scheduled job that periodically recomputes an account's balance
by summing `ledger_entries` directly and diffs it against
`account_balance_projection`. Log/alert on drift; expose a
`GET /accounts/{id}/reconcile` endpoint that runs it on demand.

**Why this order:** directly extends the CQRS story already told in
`PROBLEM_RESEARCH.md` §5 — "what happens when the read model and the source
of truth disagree" is the natural next question once you've explained why
there are two of them.

## Increment 3 — Observability

**Gap:** no metrics anywhere. "How do you know this is healthy in
production?" currently has no answer beyond reading logs.

**Build:** Micrometer + a `/actuator/prometheus` endpoint, instrumenting:
transfer latency, outbox publish lag (time between insert and Kafka publish),
idempotency-key hit rate (retries caught vs. new requests), projection
consumer lag.

**Why this order:** cheap relative to its interview value — "walk me through
how you'd know this system is unhealthy" is a standard senior-level
question, and right now the honest answer is "I wouldn't, yet."

## Increment 4 — Concurrency and failure validation

**Gap:** the correctness claims (idempotency under retries, outbox
consistency under a broker restart) are currently asserted, not demonstrated
under adversarial conditions.

**Build:** a small k6 or Gatling script firing concurrent duplicate transfer
requests with the same idempotency key, verifying exactly one transaction
results; a chaos test that kills the Kafka broker mid-outbox-drain and
verifies no lost/duplicated events on restart. Write up what actually
happened as a short doc — this becomes its own portfolio artifact, not just
a test suite.

**Why this order:** turns "I designed for this" into "I proved it," which is
a meaningfully stronger claim in an interview.

## Increment 5 — Multi-currency support

**Gap:** the ledger currently has no currency dimension at all.

**Build:** add `currency` to `Account`/`LedgerEntry`, capture the FX rate at
transaction time for any cross-currency transfer, store it immutably on the
transaction (never re-derive it later — that's the correctness-critical
part).

**Why this order last:** it's real, recognized fintech-infrastructure
complexity (see `PROBLEM_RESEARCH.md`'s sources on ledger-as-a-service), but
it's additive scope rather than fixing a gap in what's already claimed —
lower priority than 0-4.

## Increment 6 — Velocity limits

**Gap:** no rate/velocity limiting on transfers per account.

**Build:** Redis-backed sliding-window limit (e.g. max N transfers/minute per
account) — cheap given Redis is already in the stack for idempotency.

**Why this order last:** smallest, most self-contained increment; good
filler between the larger ones above, not a priority on its own.

---

## Suggested order

0 → 1 → 2 → 3 → 4 → 5/6 (5 and 6 are independent of each other, pick by
interest). Increment 0 is the one with actual urgency — it's the only item
here that involves something currently inaccurate being told to real people.
