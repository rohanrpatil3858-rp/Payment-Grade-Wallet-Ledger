package com.wallet.fraud;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** Milestone 5, Part A: measure how much time the fraud rule checks add to a transfer. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FraudRuleTimingTest {

    @Autowired
    private FraudRuleService fraudRuleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void measureFraudRuleOverhead() {
        long sender = createWallet();
        long recipient = createWallet();
        // A little history so the amount-spike rule has something to compare against.
        for (int i = 0; i < 5; i++) {
            Long transferId = jdbcTemplate.queryForObject(
                    "INSERT INTO transfers (type, status, amount) VALUES ('peer_transfer', 'completed', 1000) RETURNING id",
                    Long.class);
            jdbcTemplate.update(
                    "INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'debit', 1000)",
                    transferId, sender);
        }

        int iterations = 50;
        long totalNanos = 0;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            fraudRuleService.evaluate(sender, recipient, 1500);
            totalNanos += System.nanoTime() - start;
        }
        double averageMillis = (totalNanos / (double) iterations) / 1_000_000.0;
        System.out.println("Fraud rule overhead (3 checks combined), average over " + iterations
                + " runs: " + averageMillis + " ms");
    }

    private long createWallet() {
        String uniqueSuffix = UUID.randomUUID().toString();
        long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone, kyc_status) VALUES ('Timing Test User', ?, ?, 'verified') RETURNING id",
                Long.class, "timing-" + uniqueSuffix + "@example.com", "9" + System.nanoTime() % 1_000_000_000L);
        return jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_type, name, balance) VALUES (?, 'user_wallet', 'Timing Test Wallet', 0) RETURNING id",
                Long.class, userId);
    }
}
