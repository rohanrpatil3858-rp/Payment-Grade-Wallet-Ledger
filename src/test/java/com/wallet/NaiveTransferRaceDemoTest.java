package com.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Milestone 2, Step 2 ("break it first"): a deliberately unsafe withdraw with no row locks
 * and no single transaction boundary around the read-check-write. Fired concurrently, it is
 * expected to overdraw the wallet or let too many requests succeed. Compare against the
 * safe, row-locked version built in Step 3.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NaiveTransferRaceDemoTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private boolean naiveWithdraw(long accountId, long amountPaise) {
        Long balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
        if (balance == null || balance < amountPaise) {
            return false;
        }
        // Widen the race window so the bug reproduces reliably under concurrency.
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Write back a value computed in Java from the earlier read, not a SQL-relative
        // expression - this is what makes a read-then-write race actually lose updates.
        long newBalance = balance - amountPaise;
        jdbcTemplate.update(
                "UPDATE accounts SET balance = ? WHERE id = ?", newBalance, accountId);
        return true;
    }

    @Test
    void naiveWithdrawOverdrawsUnderConcurrency() throws InterruptedException {
        jdbcTemplate.update(
                "INSERT INTO users (name, email, phone, kyc_status) VALUES ('Race User', 'race@example.com', '9000000099', 'verified')");
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = 'race@example.com'", Long.class);
        jdbcTemplate.update(
                "INSERT INTO accounts (user_id, account_type, name, balance) VALUES (?, 'user_wallet', 'Race Wallet', 50000)",
                userId);
        Long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE user_id = ?", Long.class, userId);

        int requests = 100;
        long amountPerRequestPaise = 1000; // Rs 10 per withdrawal, Rs 500 starting balance.
        ExecutorService pool = Executors.newFixedThreadPool(20);
        AtomicInteger successCount = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(requests);

        for (int i = 0; i < requests; i++) {
            pool.submit(() -> {
                try {
                    if (naiveWithdraw(accountId, amountPerRequestPaise)) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        Long finalBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);

        System.out.println("Naive withdraw demo -> successCount=" + successCount.get() + ", finalBalance=" + finalBalance
                + " (expected if broken: successCount > 50 and/or finalBalance < 0)");

        jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);

        // No assertions here on purpose: this test exists to observe the race, not to pass/fail.
        // See docs/isolation.md Step 3 for the fixed, row-locked version and its stampede test.
    }
}
