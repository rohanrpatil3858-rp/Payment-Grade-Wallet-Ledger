# Statement Pagination Performance (API contract requirement)

`GET /wallets/{id}/statement` uses OFFSET-based pagination:

```sql
SELECT le.id, le.transfer_id, le.direction, le.amount, le.created_at, t.type
FROM ledger_entries le JOIN transfers t ON t.id = le.transfer_id
WHERE le.account_id = ?
ORDER BY le.created_at DESC, le.id DESC
LIMIT ? OFFSET ?
```

We ran `EXPLAIN ANALYZE` on a real large dataset — the submission seed (1,000 users, 2,000 wallets,
100,000 transfers) produced a system cash-in account (`account_id = 1`) with **110,000 ledger
entries**, more than enough to exercise `OFFSET 100000`.

## OFFSET 0 — first page (fast)

```
 Limit  (cost=0.72..9.30 rows=20) (actual time=2.163..2.265 rows=20 loops=1)
   ->  Nested Loop  (actual time=2.158..2.256 rows=20 loops=1)
         ->  Index Scan using idx_ledger_entries_account_statement on ledger_entries le
               (actual time=0.228..0.237 rows=20 loops=1)
               Index Cond: (account_id = 1)
         ->  Memoize -> Index Scan using transfers_pkey on transfers t
 Planning Time: 5.042 ms
 Execution Time: 2.663 ms
```

**~2.7 ms.** The `(account_id, created_at DESC, id DESC)` index (from Milestone 1) returns rows
already in the requested order, so the `LIMIT 20` lets Postgres stop after reading just 20 index
entries. Cost is independent of how much history the account has.

## OFFSET 100000 — deep page (slow)

```
 Limit  (cost=21667.20..21667.25 rows=20) (actual time=672.437..672.445 rows=20 loops=1)
   ->  Sort  (actual time=641.964..669.136 rows=100020 loops=1)
         Sort Key: le.created_at DESC, le.id DESC
         Sort Method: external merge  Disk: 6144kB
         ->  Hash Join  (actual time=233.350..350.976 rows=110000 loops=1)
               ->  Seq Scan on ledger_entries le  (actual time=0.041..50.859 rows=110000 loops=1)
                     Filter: (account_id = 1)
                     Rows Removed by Filter: 112111
               ->  Hash -> Seq Scan on transfers t (rows=111056)
 Planning Time: 5.007 ms
 Execution Time: 680.545 ms
```

**~680 ms — roughly 256× slower.** At this depth the planner abandons the index entirely: it
sequentially scans all 110,000 matching ledger rows (discarding 112,111 others), hash-joins to
`transfers`, then performs a full **external-merge sort** that spills 6 MB to disk — all to build
100,020 ordered rows just to **throw away the first 100,000** and return 20.

## Why OFFSET gets slow

`OFFSET n` is not free: the database must still *produce* every one of those `n` skipped rows
(fetch, join, and order them) before it can discard them. The deeper the page, the more wasted
work — cost grows linearly with the offset. The index helps the *first* pages (it can stop early),
but once the offset exceeds what an early-stopping index scan is worth, the planner switches to a
full scan + sort, and latency jumps from milliseconds to hundreds of milliseconds.

## Fix: cursor-based (keyset) pagination

Instead of `OFFSET`, pass the last row seen as a cursor and use the index to jump straight to it:

```sql
WHERE le.account_id = ?
  AND (le.created_at, le.id) < (:lastCreatedAt, :lastId)
ORDER BY le.created_at DESC, le.id DESC
LIMIT ?
```

Because `(account_id, created_at DESC, id DESC)` is exactly our index order, this is an index
range scan that stops after `LIMIT` rows **regardless of how deep the page is** — every page costs
roughly the same as the first (a few milliseconds). The trade-off is that the client must pass the
cursor from the previous page instead of an arbitrary page number, so you can't jump directly to
"page 5000". For a statement feed (scroll/next-page), that trade-off is almost always worth it.
