# Milestone 2, Step 1 — Isolation Levels Lab

Two `psql` sessions (Session A, Session B) were opened side by side against the local `digital_wallet` database. Test data: one user and one wallet account (`account_id = 1`) seeded with a balance of 100000 paise (₹1000) before each case, then reset between cases.

## Case 1 — Read Committed (the Postgres default)

**Session A**
```sql
BEGIN;
SELECT balance FROM accounts WHERE id = 1;
```
```
 balance
---------
  100000
```

**Session B** (updates and commits while A's transaction is still open)
```sql
BEGIN;
UPDATE accounts SET balance = balance - 10000 WHERE id = 1;
COMMIT;
```
```
BEGIN
UPDATE 1
COMMIT
```

**Session A** (reads again, same open transaction)
```sql
SELECT balance FROM accounts WHERE id = 1;
```
```
 balance
---------
   90000
```

**Does A see a different value? Yes.** Under Read Committed, each individual statement sees the latest *committed* data at the moment it runs — even two statements inside the same transaction can see different values if another transaction commits in between. This is a "non-repeatable read".

```sql
COMMIT; -- Session A
```

## Case 2 — Repeatable Read

Balance reset to 100000 first (`UPDATE accounts SET balance = 100000 WHERE id = 1;`).

**Session A**
```sql
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT balance FROM accounts WHERE id = 1;
```
```
 balance
---------
  100000
```

**Session B** (updates and commits)
```sql
BEGIN;
UPDATE accounts SET balance = balance - 10000 WHERE id = 1;
COMMIT;
```
```
BEGIN
UPDATE 1
COMMIT
```

**Session A** (reads again, same open transaction)
```sql
SELECT balance FROM accounts WHERE id = 1;
```
```
 balance
---------
  100000
```

**What does Session A see now? The original value (100000), unchanged** — even though the real row in the database is now 90000. Repeatable Read takes a consistent snapshot of the database at the start of the transaction and every query inside that transaction sees that same snapshot, no matter what else commits in the meantime.

```sql
COMMIT; -- Session A
```

## Case 3 — Serializable

Balance reset to 100000. Both sessions read the balance, then each writes based on what they read.

**Session A**
```sql
BEGIN ISOLATION LEVEL SERIALIZABLE;
SELECT balance FROM accounts WHERE id = 1;
```
```
 balance
---------
  100000
```

**Session B** (also reads the same starting value)
```sql
BEGIN ISOLATION LEVEL SERIALIZABLE;
SELECT balance FROM accounts WHERE id = 1;
```
```
 balance
---------
  100000
```

**Session A** writes based on its read, and commits first:
```sql
UPDATE accounts SET balance = 100000 - 10000 WHERE id = 1;
COMMIT;
```
```
UPDATE 1
COMMIT
```

**Session B** writes based on its (now stale) read, and tries to commit:
```sql
UPDATE accounts SET balance = 100000 - 20000 WHERE id = 1;
COMMIT;
```
```
ERROR:  could not serialize access due to concurrent update
ROLLBACK
```

**What happens to one of them? Session B's commit fails** with a serialization error and its entire transaction is rolled back — Postgres detected that both transactions read the same value and would have overwritten each other's result, so it forces one to abort rather than silently lose an update. The application has to catch this error and retry Session B's transaction from the start.

## Case 4 — Repeatable Read with a join

Balance reset to 100000.

**Session A**
```sql
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT a.id, a.balance, u.name
FROM accounts a JOIN users u ON a.user_id = u.id
WHERE a.id = 1;
```
```
 id | balance |   name
----+---------+-----------
  1 |  100000 | Test User
```

**Session B** (updates and commits)
```sql
BEGIN;
UPDATE accounts SET balance = balance - 10000 WHERE id = 1;
COMMIT;
```

**Session A** (re-runs the same joined query, same open transaction)
```sql
SELECT a.id, a.balance, u.name
FROM accounts a JOIN users u ON a.user_id = u.id
WHERE a.id = 1;
```
```
 id | balance |   name
----+---------+-----------
  1 |  100000 | Test User
```

**Does the guarantee still hold? Yes.** Session A still sees the original balance of 100000, exactly like the simple single-table query in Case 2. Repeatable Read's snapshot applies to the whole transaction, not to individual tables — joining more tables doesn't weaken the guarantee.

## Summary

| Isolation level | Same value re-read in one transaction? | Conflicting concurrent writes? |
|---|---|---|
| Read Committed (default) | No — can change between statements | Both succeed; later write silently overwrites the earlier one (lost update risk) |
| Repeatable Read | Yes — snapshot fixed for the whole transaction, even across joins | One of the two conflicting transactions fails to commit with a serialization error |
| Serializable | Yes (same guarantee as Repeatable Read for reads) | Same as above — the second committer gets `could not serialize access due to concurrent update` and must retry |

# Milestone 2, Step 2 — Break It First

A deliberately unsafe `naiveWithdraw` was written (see `src/test/java/com/wallet/NaiveTransferRaceDemoTest.java`): it reads the balance, checks it in Java, sleeps briefly to widen the race window, then writes back an absolute `newBalance` computed from that earlier read — no row lock, no single transaction wrapping read+write.

**Setup:** one wallet seeded with ₹500 (50000 paise). 100 concurrent ₹10 (1000 paise) withdrawal requests fired via a 20-thread pool.

**First attempt** used `UPDATE accounts SET balance = balance - ?` (a SQL-relative expression). Result: `successCount=50, finalBalance=0` — this accidentally did **not** reproduce the bug, because Postgres evaluates `balance - ?` against the row's current value at the moment the `UPDATE` executes, not a value cached in application code, so each individual `UPDATE` was still atomic.

**Fixed demo** instead computes `newBalance = balance - amountPaise` in Java from the earlier `SELECT`, then writes that absolute value back — the real pattern that causes lost updates. Result:

```
Naive withdraw demo -> successCount=100, finalBalance=45000
```

**This is the bug the brief describes:** all 100 requests reported success (every thread read a balance it considered sufficient before any of the concurrent withdrawals had applied), but the wallet only dropped from 50000 to 45000 paise instead of to 0. Many threads read the same stale balance, computed their own "correct" new balance from it, and overwrote each other — most writes were silently lost. ₹50 of money effectively vanished from the books: the application thinks 100 withdrawals succeeded, but only the equivalent of 5 actually took effect. This fails all three stampede acceptance criteria (not exactly 50 successes, wallet is not at ₹0, debits wouldn't reconcile against the ledger). Step 3 fixes this with `SELECT ... FOR UPDATE` row locking.

# Milestone 2, Step 3 — Fix It With Row Locks

`TransferService.withdraw` (`src/main/java/com/wallet/transfer/TransferService.java`) wraps the whole operation in one transaction:

1. Lock both account rows with `SELECT ... FOR UPDATE`, always in **ascending `account_id` order** (`Math.min`/`Math.max` of the two account IDs) — this prevents deadlocks, since every transfer touching the same two accounts always requests the locks in the same order.
2. Re-read the balance from the locked row and reject with `INSUFFICIENT_FUNDS` if there isn't enough money (rolling back, no partial writes).
3. Insert the `transfers` row and its two balanced `ledger_entries` rows, then update both accounts' stored balances.
4. The transaction commits only if every step above succeeded; any failure rolls back everything.

**Which isolation level does this run at, and why?** Read Committed (Postgres's default) — but combined with explicit `SELECT ... FOR UPDATE` row locks. Read Committed alone is not enough on its own (that's exactly the Step 2 bug: two transactions can both read the same balance before either writes). The row lock closes that gap directly: a second transaction asking for the same account row simply **waits** until the first commits, then its own `SELECT ... FOR UPDATE` sees the just-committed, up-to-date balance. This gives the same correctness guarantee as Serializable for this specific access pattern, without Serializable's cost: Serializable transactions can fail at `COMMIT` time under contention and must be retried from scratch by the application (as seen in Case 3 above), which hurts throughput when many requests hit the same rows. Since every withdrawal here always locks the exact same two accounts in a fixed order, Read Committed + row locks gives full correctness with no wasted retries.

## Acceptance test: the stampede

`StampedeTransferTest` (`src/test/java/com/wallet/transfer/StampedeTransferTest.java`) seeds a wallet with ₹500 (50000 paise) and fires 100 concurrent ₹10 (1000 paise) withdrawals through `TransferService.withdraw`, 20 times in a row, asserting every single run that:
- exactly 50 transfers succeed
- the wallet ends at exactly ₹0 (never negative)
- total debits equal total credits across all ledger entries

**Result: all 20 runs passed, zero failures** (`tests="1" failures="0" errors="0"` in the JUnit report — the test method loops 20 times internally and fails immediately if any single run violates any of the three conditions).


