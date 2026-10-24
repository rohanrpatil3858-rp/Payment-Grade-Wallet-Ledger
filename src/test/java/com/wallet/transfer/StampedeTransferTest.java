package com.wallet.transfer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Milestone 2 acceptance test ("the stampede"): Rs 500 in one wallet, 100 concurrent Rs 10
 * withdrawals, run 20 times in a row. Must pass every single time:
 *   - exactly 50 transfers succeed
 *   - the wallet ends at Rs 0 and is never negative
 *   - total debits equal total credits
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class StampedeTransferTest {

    private static final long STARTING_BALANCE_PAISE = 50000; // Rs 500
    private static final long WITHDRAWAL_AMOUNT_PAISE = 1000; // Rs 10
    private static final int CONCURRENT_REQUESTS = 100;
    private static final int RUNS = 20;

    @Autowired
    private TransferService transferService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void stampedeNeverOverdraws() throws InterruptedException {
        long walletAccountId = findOrCreateTestWallet();
        long cashOutAccountId = findOrCreateTestCashOutAccount();

        for (int run = 1; run <= RUNS; run++) {
            jdbcTemplate.update("UPDATE accounts SET balance = ? WHERE id = ?", STARTING_BALANCE_PAISE, walletAccountId);

            int successCount = fireConcurrentWithdrawals(walletAccountId, cashOutAccountId);

            long finalBalance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM accounts WHERE id = ?", Long.class, walletAccountId);

            long totalDebits = sumAmountByDirection("debit", walletAccountId, cashOutAccountId);
            long totalCredits = sumAmountByDirection("credit", walletAccountId, cashOutAccountId);

            assertEquals(50, successCount, "run " + run + ": expected exactly 50 successful withdrawals");
            assertEquals(0, finalBalance, "run " + run + ": wallet must end at exactly Rs 0");
            assertEquals(totalCredits, totalDebits, "run " + run + ": total debits must equal total credits");
        }
    }

    private int fireConcurrentWithdrawals(long walletAccountId, long cashOutAccountId) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        AtomicInteger successCount = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            pool.submit(() -> {
                try {
                    TransferResult result = transferService.withdraw(walletAccountId, cashOutAccountId, WITHDRAWAL_AMOUNT_PAISE);
                    if (result.success()) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();
        return successCount.get();
    }

    private long sumAmountByDirection(String direction, long walletAccountId, long cashOutAccountId) {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE direction = ? AND account_id IN (?, ?)",
                Long.class, direction, walletAccountId, cashOutAccountId);
        return sum == null ? 0 : sum;
    }

    private long findOrCreateTestWallet() {
        List<Long> existing = jdbcTemplate.queryForList(
                "SELECT id FROM accounts WHERE name = 'Stampede Test Wallet'", Long.class);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        List<Long> existingUser = jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE email = 'stampede@example.com'", Long.class);
        long userId = existingUser.isEmpty()
                ? jdbcTemplate.queryForObject(
                        "INSERT INTO users (name, email, phone, kyc_status) VALUES ('Stampede Tester', 'stampede@example.com', '9000000100', 'verified') RETURNING id",
                        Long.class)
                : existingUser.get(0);
        return jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_type, name, balance) VALUES (?, 'user_wallet', 'Stampede Test Wallet', ?) RETURNING id",
                Long.class, userId, STARTING_BALANCE_PAISE);
    }

    private long findOrCreateTestCashOutAccount() {
        List<Long> existing = jdbcTemplate.queryForList(
                "SELECT id FROM accounts WHERE name = 'Stampede Test Cash-Out'", Long.class);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_type, name, balance) VALUES (NULL, 'system_cash_out', 'Stampede Test Cash-Out', 0) RETURNING id",
                Long.class);
    }
}
