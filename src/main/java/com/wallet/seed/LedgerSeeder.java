package com.wallet.seed;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates realistic, balanced test data quickly via batched/multi-row SQL - used by the
 * Milestone 4 acceptance test, and reusable later for the project's larger seed script.
 */
@Service
public class LedgerSeeder {

    private static final int CHUNK_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;

    public LedgerSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Creates walletCount fresh wallets and transferCount balanced top-up transfers spread across them. */
    public SeedResult seedTopUps(int walletCount, int transferCount) {
        long cashInAccountId = findOrCreateSystemAccount("system_cash_in", "Seed Cash-In Account");
        List<Long> walletIds = createWallets(walletCount);

        Random random = new Random();
        List<long[]> plannedTransfers = new ArrayList<>(transferCount); // [walletId, amountPaise]
        for (int i = 0; i < transferCount; i++) {
            long walletId = walletIds.get(random.nextInt(walletIds.size()));
            long amountPaise = 100 + random.nextInt(9901); // Rs 1.00 to Rs 100.00
            plannedTransfers.add(new long[]{walletId, amountPaise});
        }

        List<Long> transferIds = insertTransfersInChunks(plannedTransfers);
        insertLedgerEntriesInChunks(transferIds, plannedTransfers, cashInAccountId);
        recomputeStoredBalancesFromLedger(walletIds, cashInAccountId);

        return new SeedResult(walletIds, cashInAccountId, transferIds);
    }

    private List<Long> createWallets(int walletCount) {
        List<Long> walletIds = new ArrayList<>(walletCount);
        for (int i = 0; i < walletCount; i++) {
            String uniqueSuffix = UUID.randomUUID().toString();
            String phone = "9" + String.format("%09d", Math.abs(UUID.randomUUID().hashCode()) % 1_000_000_000);
            long userId = jdbcTemplate.queryForObject(
                    "INSERT INTO users (name, email, phone, kyc_status) VALUES ('Seed User', ?, ?, 'verified') RETURNING id",
                    Long.class, "seed-" + uniqueSuffix + "@example.com", phone);
            long accountId = jdbcTemplate.queryForObject(
                    "INSERT INTO accounts (user_id, account_type, name, balance) VALUES (?, 'user_wallet', 'Seed Wallet', 0) RETURNING id",
                    Long.class, userId);
            walletIds.add(accountId);
        }
        return walletIds;
    }

    private List<Long> insertTransfersInChunks(List<long[]> plannedTransfers) {
        List<Long> transferIds = new ArrayList<>(plannedTransfers.size());
        for (int start = 0; start < plannedTransfers.size(); start += CHUNK_SIZE) {
            int end = Math.min(start + CHUNK_SIZE, plannedTransfers.size());
            List<long[]> chunk = plannedTransfers.subList(start, end);

            StringBuilder sql = new StringBuilder(
                    "INSERT INTO transfers (type, status, amount) VALUES ");
            Object[] args = new Object[chunk.size()];
            for (int i = 0; i < chunk.size(); i++) {
                sql.append(i == 0 ? "('top_up', 'completed', ?)" : ", ('top_up', 'completed', ?)");
                args[i] = chunk.get(i)[1];
            }
            sql.append(" RETURNING id");

            transferIds.addAll(jdbcTemplate.queryForList(sql.toString(), Long.class, args));
        }
        return transferIds;
    }

    private void insertLedgerEntriesInChunks(List<Long> transferIds, List<long[]> plannedTransfers, long cashInAccountId) {
        List<Object[]> batchArgs = new ArrayList<>(plannedTransfers.size() * 2);
        for (int i = 0; i < transferIds.size(); i++) {
            long transferId = transferIds.get(i);
            long walletId = plannedTransfers.get(i)[0];
            long amountPaise = plannedTransfers.get(i)[1];
            batchArgs.add(new Object[]{transferId, cashInAccountId, "debit", amountPaise});
            batchArgs.add(new Object[]{transferId, walletId, "credit", amountPaise});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, ?, ?)",
                batchArgs);
    }

    /** Only recomputes the accounts this seeding run touched - never other, unrelated accounts. */
    private void recomputeStoredBalancesFromLedger(List<Long> walletIds, long cashInAccountId) {
        List<Long> scopedAccountIds = new ArrayList<>(walletIds);
        scopedAccountIds.add(cashInAccountId);
        String placeholders = String.join(",", scopedAccountIds.stream().map(id -> "?").toList());
        jdbcTemplate.update(
                "UPDATE accounts a SET balance = sub.total FROM ("
                        + "  SELECT account_id, "
                        + "  COALESCE(SUM(CASE WHEN direction = 'credit' THEN amount WHEN direction = 'debit' THEN -amount ELSE 0 END), 0) AS total "
                        + "  FROM ledger_entries WHERE account_id IN (" + placeholders + ") GROUP BY account_id"
                        + ") sub WHERE sub.account_id = a.id",
                scopedAccountIds.toArray());
    }

    private long findOrCreateSystemAccount(String accountType, String name) {
        List<Long> existing = jdbcTemplate.queryForList(
                "SELECT id FROM accounts WHERE account_type = ? AND name = ?", Long.class, accountType, name);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_type, name, balance) VALUES (NULL, ?, ?, 0) RETURNING id",
                Long.class, accountType, name);
    }

    /**
     * The submission-scale seed: userCount users, each with walletsPerUser wallets, and
     * transferCount balanced top-up transfers spread across all those wallets. Runs only when
     * explicitly invoked (never on normal startup). Uses chunked multi-row inserts so even
     * 100,000 transfers (200,000 ledger entries) load in a reasonable time.
     */
    public SeedResult seedLargeDataset(int userCount, int walletsPerUser, int transferCount) {
        long cashInAccountId = findOrCreateSystemAccount("system_cash_in", "Seed Cash-In Account");
        List<Long> walletIds = createUsersWithWallets(userCount, walletsPerUser);

        Random random = new Random();
        List<long[]> plannedTransfers = new ArrayList<>(transferCount);
        for (int i = 0; i < transferCount; i++) {
            long walletId = walletIds.get(random.nextInt(walletIds.size()));
            long amountPaise = 100 + random.nextInt(9901);
            plannedTransfers.add(new long[]{walletId, amountPaise});
        }

        List<Long> transferIds = insertTransfersInChunks(plannedTransfers);
        insertLedgerEntriesInChunks(transferIds, plannedTransfers, cashInAccountId);
        recomputeStoredBalancesFromLedger(walletIds, cashInAccountId);

        return new SeedResult(walletIds, cashInAccountId, transferIds);
    }

    private List<Long> createUsersWithWallets(int userCount, int walletsPerUser) {
        List<Long> walletIds = new ArrayList<>(userCount * walletsPerUser);
        for (int u = 0; u < userCount; u++) {
            String uniqueSuffix = UUID.randomUUID().toString();
            String phone = "9" + String.format("%09d", Math.abs(UUID.randomUUID().hashCode()) % 1_000_000_000);
            long userId = jdbcTemplate.queryForObject(
                    "INSERT INTO users (name, email, phone, kyc_status) VALUES ('Seed User', ?, ?, 'verified') RETURNING id",
                    Long.class, "seed-" + uniqueSuffix + "@example.com", phone);
            for (int w = 0; w < walletsPerUser; w++) {
                long accountId = jdbcTemplate.queryForObject(
                        "INSERT INTO accounts (user_id, account_type, name, balance) VALUES (?, 'user_wallet', ?, 0) RETURNING id",
                        Long.class, userId, "Seed Wallet " + (w + 1));
                walletIds.add(accountId);
            }
        }
        return walletIds;
    }

    public record SeedResult(List<Long> walletAccountIds, long cashInAccountId, List<Long> transferIds) {
    }
}
