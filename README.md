# Idempotent Payment/Wallet Event Processor

A backend service that safely applies wallet debit/credit events sent by a payment
gateway, even when the gateway retries and sends the same event multiple times
concurrently, and even when many requests race to debit the same wallet at once.

## The problem this solves

Payment gateways retry on timeout. That means the same `transactionId` can arrive
2–3 times within milliseconds of each other. Naively applying every webhook would
double- or triple-charge a wallet. Separately, if 10 debit requests hit the same
wallet at the same instant, a naive read-then-write can let the balance go negative
because every request reads the same "before" balance before any of them write.

This service guarantees:
- **Exactly-once processing** per `transactionId`, regardless of how many times it's retried.
- **No negative balances**, regardless of how many concurrent debits target the same wallet.

## Architecture

```
                        ┌─────────────────────────────┐
   POST /transactions   │   TransactionController      │
  ───────────────────▶  │   (validates request shape)  │
                        └──────────────┬───────────────┘
                                       │
                                       ▼
                        ┌─────────────────────────────┐
                        │   TransactionService          │
                        │   @Transactional               │
                        │                                 │
                        │  1. Lock wallet row (FOR UPDATE)│
                        │  2. Check idempotency table     │
                        │  3. Apply balance change        │
                        │  4. Record processed txn        │
                        └───────┬─────────────┬──────────┘
                                │             │
                                ▼             ▼
                     ┌──────────────┐  ┌────────────────────────┐
                     │  wallets      │  │  processed_transactions │
                     │  (balance)    │  │  (idempotency ledger,   │
                     │               │  │   transactionId = PK)   │
                     └──────────────┘  └────────────────────────┘
                                H2 (in-memory)
```

Two concurrent requests for the **same wallet** serialize at step 1 — the second
one blocks at the database until the first transaction commits. This is what
makes both the idempotency guarantee and the no-negative-balance guarantee hold,
using one mechanism instead of two.

### Why the lock happens *before* the idempotency check

A tempting-looking but wrong order is: check if the transaction was already
processed, *then* lock and debit. That leaves a window between the check and the
lock where two threads can both see "not yet processed" before either has
committed anything. Locking the wallet row first closes that window — nothing
else touching this wallet can even start reading the idempotency table until
the current request finishes.

### Why the database, not a distributed lock or in-memory map

An in-memory `Set<UUID>` of processed transaction IDs would break the moment
this service runs as more than one instance — two replicas would each think
they're first. Using the database's own row lock and primary-key constraint
means correctness holds regardless of how many instances of this service are
running behind a load balancer.

## Tech stack

| Layer | Choice | Why |
|---|---|---|
| Language | Java 17 | Assignment requirement |
| Framework | Spring Boot 3.2 | Web layer, DI, transaction management |
| Persistence | Spring Data JPA + Hibernate | Repository abstraction, `@Lock` support |
| Database | H2 (in-memory) | Zero-config — tests run with no external setup |
| Boilerplate reduction | Lombok | Generates getters/setters/constructors |
| Config | spring-dotenv | Loads `.env` into Spring's `Environment` automatically |
| Build | Gradle | Project requirement |
| Tests | JUnit 5 | `@DisplayName` per the assignment's test-naming requirement |

## Project structure

```
src/main/java/com/shrey/wallet/
├── WalletApplication.java
├── controller/
│   └── TransactionController.java     REST endpoint, request validation
├── service/
│   └── TransactionService.java        Locking + idempotency + balance logic (the core)
├── repository/
│   ├── WalletRepository.java          findByIdForUpdate() = SELECT ... FOR UPDATE
│   └── ProcessedTransactionRepository.java
├── entity/
│   ├── Wallet.java                    balance + optimistic @Version as a second guard
│   └── ProcessedTransaction.java      idempotency ledger, transactionId is the PK
├── dto/
│   ├── TransactionRequest.java
│   └── TransactionResponse.java
└── exception/
    ├── DuplicateTransactionException.java   → 409
    ├── InsufficientFundsException.java      → 422
    ├── WalletNotFoundException.java         → 404
    └── GlobalExceptionHandler.java          maps exceptions to HTTP status codes
```

## API

**`POST /api/v1/transactions/process`**

```json
{
  "transactionId": "11111111-1111-1111-1111-111111111111",
  "userId": "22222222-2222-2222-2222-222222222222",
  "amount": 250.00,
  "type": "DEBIT"
}
```

| Response | Meaning |
|---|---|
| `200 OK` | Applied. Body includes the new balance. |
| `409 Conflict` | This `transactionId` was already processed — original result is not re-applied. |
| `422 Unprocessable Entity` | `DEBIT` would take the balance negative. |
| `404 Not Found` | No wallet exists for `userId`. |

Note: there's no wallet-creation endpoint — tests seed a wallet directly via
`WalletRepository`. Add a `POST /api/v1/wallets` endpoint if you want to exercise
this via Postman/curl against a real wallet.

## Configuration (env vars)

`spring.datasource.*` reads from environment variables with safe defaults, via
`spring-dotenv` loading `.env` automatically — no IntelliJ run-config setup needed.

| Variable | Default | Notes |
|---|---|---|
| `DB_URL` | `jdbc:h2:mem:walletdb;DB_CLOSE_DELAY=-1` | Swap for a real DB URL later |
| `DB_USERNAME` | `sa` | |
| `DB_PASSWORD` | *(blank)* | H2 has no real password by default |

`.env` is gitignored; `.env.example` documents the required keys and is committed.

## Running it

**Tests (IntelliJ):**
Open the folder → accept "Trust Gradle Project" → right-click
`src/test/java/com/shrey/wallet/TransactionProcessingTest.java` → **Run**. Console
prints each test's `@DisplayName` plus actual success/duplicate/balance numbers.

**Tests (CLI):** `./gradlew test`

**App:** `./gradlew bootRun`

No Gradle wrapper binaries are guaranteed pre-verified in every environment — if
`./gradlew` fails, let IntelliJ regenerate it on import, or run `gradle wrapper`
once locally before pushing.

## Testing strategy

The two concurrency tests (`idempotentUnderThreeSimultaneousIdenticalRequests`,
`raceConditionNeverAllowsNegativeBalance`) use a `CountDownLatch` to hold every
thread at the starting line until all of them are ready, then release them
simultaneously — this is what actually exercises the row lock under real
contention, rather than testing threads that happen to run one after another.

## Known limitations / what I'd change with more time

- **Idempotency check hits the database on every request**, including duplicates.
  With more time I'd put a Redis `SET transactionId <marker> NX EX <ttl>` check in
  front of the database — atomic check-and-mark in one round trip, self-expiring via
  TTL instead of growing `processed_transactions` forever, and duplicates get
  rejected without the database (or the row lock) ever being touched. The row lock
  would remain for the actual balance mutation — Redis solves "is this a duplicate",
  not "is this balance update safe under concurrency"; those are different problems.
- **Single-instance locking model.** Row-level locking is correct across multiple
  service instances (the DB is the single source of truth), but under heavy load a
  hot wallet becomes a serialization point. A sharded/queue-based design (one
  ordered queue per wallet) would scale further, at the cost of more moving parts.
- **No wallet-creation endpoint** — out of scope for this assignment, but the first
  thing to add to make this exercisable outside of tests.
