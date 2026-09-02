# Decision Log

## 1. How did you handle the concurrency race condition?

I used **pessimistic row-level locking** via Spring Data JPA's `@Lock(LockModeType.PESSIMISTIC_WRITE)`,
which issues a `SELECT ... FOR UPDATE` against the wallet row. Inside a single `@Transactional`
service method, I:

1. Lock the wallet row **first** (`findByIdForUpdate`), before doing anything else.
2. Only then check whether the incoming `transactionId` has already been processed
   (via a lookup against a `processed_transactions` table keyed by `transactionId`).
3. Apply the balance change and persist the `ProcessedTransaction` record, in the same
   transaction as the lock.

Locking before checking idempotency (rather than after) is the key detail — it closes the
race window between "check" and "act". Concurrent requests for the same wallet queue up at
the database level instead of racing in application memory, so whichever transaction commits
first is authoritative and every later one sees the updated state.

As a second line of defense, `transactionId` is the primary key of `ProcessedTransaction`,
so even if the locking logic were ever bypassed, a duplicate insert would fail on the
unique constraint and get mapped to a 409.

I considered optimistic locking (`@Version`) instead, but rejected it here because it would
mean 2 of the 3 duplicate requests fail with a retry-able `OptimisticLockException` rather
than a clean, immediate 409 — and under the "10 concurrent debits" scenario it would cause
unnecessary retry storms rather than clean serialization.

**Given more time**, I'd move idempotency detection out of the database entirely and into
Redis: `SET transactionId <marker> NX EX <ttl>` as the very first thing the request does.
`NX` (set-if-not-exists) makes the check-and-mark atomic in one round trip, and the `TTL`
means the dedup key expires on its own instead of growing the `processed_transactions`
table forever. The 2nd and 3rd duplicate requests would get rejected by Redis in
sub-millisecond time, without ever touching Postgres/the row lock at all — so the database
only sees load from requests that are actually going to mutate a balance, not from
duplicates and retries. The pessimistic row lock would stay in place for the actual balance
update (Redis solves "is this a duplicate", not "is this balance update safe under
concurrency" — those are two different problems), but it would only be reached once per
unique transaction instead of once per attempt.

## 2. Where did your AI assistant give you an incorrect or sub-optimal suggestion?

*(Fill this in honestly based on your own process — a few real examples of the kind of
thing to watch for, from building this out:)*

- An early draft checked `existsByTransactionId` **before** acquiring the wallet lock.
  That looks correct in isolation but leaves a race window: two threads can both pass the
  "not yet processed" check before either has locked the row. Moving the lock acquisition
  to the very first line of the transaction closed that gap.
- A first pass at the `ProcessedTransaction` entity added a redundant mirror column plus
  a separate `@UniqueConstraint` annotation, duplicating uniqueness the `@Id` already
  guarantees — unnecessary complexity that added a bug surface for no benefit.
- Worth double-checking yourself: whether `@Version` optimistic locking or pessimistic
  locking is the better fit for a given concurrency scenario is a judgment call, not a
  fact — verify the trade-off against the specific test requirements rather than taking
  either suggestion at face value.
