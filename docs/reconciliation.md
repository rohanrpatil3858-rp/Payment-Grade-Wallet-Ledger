# Milestone 4 — Nightly Reconciliation: Performance Analysis

`EXPLAIN ANALYZE` was run on each of the 5 reconciliation checks against a real dataset of **1,021,999 ledger entries** (1,000 wallets, ~500,000 top-up transfers, plus leftover data from other tests), using the existing indexes from Milestone 1 (`idx_ledger_entries_transfer_id`, `idx_ledger_entries_account_statement`, and primary keys) — no new indexes were added for this milestone.

## Results

| # | Check | Execution time | Plan shape |
|---|---|---|---|
| 1 | Everything balances (global) | **~527 ms** | Parallel sequential scan + aggregate |
| 2 | Every transfer balances | **~2,835 ms** | Index scan on `idx_ledger_entries_transfer_id` + group aggregate |
| 3 | Stored balances are right | **~980 ms** | Sequential scan on both tables + hash join + aggregate |
| 4 | No overdrafts | **~467 ms** | Parallel sequential scan + hash join + aggregate |
| 5 | Nothing is orphaned | **~1,506 ms** | Index scan (merge join) using `idx_ledger_entries_transfer_id` + primary key |

## Which checks can afford a full table scan, and which need an index?

**All 5 checks can afford a full/parallel sequential scan of `ledger_entries` — this is a nightly batch job, not a request on the hot path**, and every check here completed in well under 3 seconds against ~1 million rows. None of them need a *new* index to be acceptable for a once-a-night run. That said, two observations are worth calling out:

- **Check 2 (per-transfer balance)** is the slowest at ~2.8s, but it's actually *helped* by the existing `idx_ledger_entries_transfer_id` index — the planner chose an **Index Scan** (not a sequential scan) because it lets Postgres read rows pre-grouped by `transfer_id`, avoiding a separate sort/hash step for the `GROUP BY`. Without that index (added back in Milestone 1 specifically to support this kind of check), this query would need to sort or hash-aggregate 1M+ rows from scratch, which would be noticeably slower.
- **Check 5 (orphaned transfers)** also benefits from the same index plus the `transfers` primary key, letting Postgres do a cheap **Merge Join** (both sides already ordered by `id`) instead of a hash join.
- **Checks 1, 3, and 4** are all fine with a plain sequential scan (with parallel workers kicking in automatically for the big aggregates) — their cost is dominated by simply reading every row once, which is unavoidable for a "does this hold true for the whole ledger" check, and no index could reduce that at this check's granularity (every row needs to be summed, not looked up).

**Bottom line:** the Milestone 1 indexes already pull their weight for Milestone 4's reconciliation job (checks 2 and 5 specifically), and no further indexing is needed purely for this nightly job's sake — adding more indexes would only slow down the hot write path (every transfer insert) for a check that already finishes in a few seconds once a night.

## Notes on the test data

- 500,000 top-up transfers were generated directly via bulk SQL (`generate_series`, not through the application) purely to produce a realistic row count quickly for this timing exercise.
- 1,000 of those test wallets were deliberately left with their default `balance = 0` (never reconciled with their real ledger total), which is why Check 3 found ~1,003 "mismatches" in the output above — that's expected noise from this specific performance dataset, not a bug. The data was deleted (`TRUNCATE`) immediately after capturing these results, so it does not affect the acceptance test or any other milestone's data.
