# CLAUDE.md — Project Instructions for FluxUPI

This file gives Claude (in VS Code / Claude Code) the context it needs to
build this project correctly, in the right order, without re-explaining
requirements every session.

## What this project is

A simulated UPI-based digital credit line engine. Full context is in
`README.md` — read that first if starting fresh. This file is about *how*
to build it, not *what* it is.

**Hard constraint: everything is simulated.** Never add real bank/UPI/NPCI
API calls, real KYC, or anything implying a live payment rail. All
"external" parties (lenders, VPA resolution, webhook consumers) are mocked
services within this codebase.

## Tech stack (do not substitute without asking)

Java 21, Spring Boot 3, PostgreSQL, Flyway, Maven, JUnit5, Testcontainers,
Docker Compose, springdoc-openapi for Swagger.

## Build order — follow this sequence, do not skip ahead

1. **Domain models + schema** — `User`, `Lender`, `CreditLine` entities;
   Flyway migration `V1__init_schema.sql`. `CreditLine` state machine
   (`PENDING→APPROVED→ACTIVE→FROZEN→CLOSED/DEFAULTED`) implemented as actual
   state objects with `canTransitionTo()` guards — not an enum with
   scattered `if` checks. Unit tests for every valid and invalid transition.

2. **Transactions + ledger** — this is the core of the project, spend the
   most care here. `Transaction` entity with `idempotencyKey` (DB UNIQUE
   constraint, not just app-level checking). `LedgerService` writes ≥2
   `LedgerEntry` rows per transaction inside the *same* DB transaction as
   the `CreditLine.availableLimit` update — no separate commits, to avoid a
   race between two concurrent spends. Write `LedgerReconciliationTest`
   early and keep it passing as you build.

3. **Interest + repayment** — `InterestStrategy` interface with
   `FlatRateStrategy` and `ReducingBalanceStrategy` implementations.
   `RepaymentSchedule` generation from an approved `CreditLine` + its
   transaction history.

4. **Mock underwriting** — simple rule engine (e.g. income threshold +
   existing exposure check) that a `Lender` runs to approve/reject a
   `CreditLine` application. Keep this rule-based and inspectable, not a
   black box — the point is to demonstrate business-logic modeling.

5. **Infra** — `docker-compose.yml` (already scaffolded), GitHub Actions CI
   (`.github/workflows/ci.yml`), Testcontainers integration tests, a seed
   script (`scripts/seed_transactions.sh` or a `@Profile("seed")` runner)
   that generates 1,000+ transactions for `LedgerReconciliationTest` to
   validate against.

## Conventions

- Package by feature, not by layer: `com.fluxupi.creditline`,
  `com.fluxupi.transaction`, `com.fluxupi.ledger`, `com.fluxupi.repayment`,
  not `com.fluxupi.controller` / `com.fluxupi.service` / etc.
- Every state transition must go through a guarded method — never set a
  status field directly from a service class.
- Every new entity needs a Flyway migration, never rely on
  `ddl-auto: update`.
- Every service method that touches money (ledger, limits, repayments)
  needs a test that runs it concurrently (even just 2 threads) to catch
  race conditions — this project's core claim is correctness under
  concurrency, so don't skip this.
- Favor explicit, named exceptions (`InsufficientCreditLimitException`,
  `DuplicateIdempotencyKeyException`) over generic `RuntimeException`.

## Definition of done for each milestone

A milestone isn't done until: it has unit tests, it has a Flyway migration
if it touches schema, and `./mvnw test` passes clean. Don't move to the
next build-order step until the current one meets this bar — a fast,
sloppy implementation here undermines the exact claims (100% ledger
reconciliation, idempotent processing) this project exists to demonstrate.

## When in doubt

Prefer the simpler, more explicit implementation over a clever one. This
project is being evaluated by engineers who will ask "walk me through what
happens when a user spends against their credit line" — every layer needs
to be something the author (not just Claude) can explain from memory.
