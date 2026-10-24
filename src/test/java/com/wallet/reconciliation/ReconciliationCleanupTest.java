package com.wallet.reconciliation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Milestone 3's optional cleanup, done as part of the Milestone 4 nightly job. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReconciliationCleanupTest {

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void deletesOnlyCompletedKeysOlderThan24Hours() {
        long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone, kyc_status) VALUES ('Cleanup Tester', ?, ?, 'verified') RETURNING id",
                Long.class, "cleanup-" + UUID.randomUUID() + "@example.com", "9" + System.nanoTime() % 1_000_000_000L);

        insertKeyWithAge(userId, "old-completed", "completed", "26 hours");
        insertKeyWithAge(userId, "recent-completed", "completed", "1 hour");
        insertKeyWithAge(userId, "old-in-progress", "in_progress", "26 hours");

        reconciliationService.run();

        assertEquals(0, countKey(userId, "old-completed"), "old completed key must be deleted");
        assertEquals(1, countKey(userId, "recent-completed"), "recent completed key must survive");
        assertEquals(1, countKey(userId, "old-in-progress"), "old in-progress key must survive (not completed)");
    }

    private void insertKeyWithAge(long userId, String key, String status, String age) {
        jdbcTemplate.update(
                "INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash, status, created_at) "
                        + "VALUES (?, ?, 'hash', ?, now() - interval '" + age + "')",
                userId, key, status);
    }

    private int countKey(long userId, String key) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ?", Integer.class, userId, key);
        return count == null ? 0 : count;
    }
}
