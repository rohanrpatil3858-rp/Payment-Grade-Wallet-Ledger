package com.wallet.idempotency;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Milestone 3 acceptance tests, against POST /transfers/withdraw.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyAcceptanceTest {

    private static final long STARTING_BALANCE_PAISE = 50000;
    private static final long WITHDRAWAL_AMOUNT_PAISE = 1000;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void sameKeySameBodySentFiveTimesMovesMoneyOnlyOnceAndReturnsIdenticalResponses() throws Exception {
        long userId = createTestUser();
        long walletId = createTestWallet(userId);
        String idempotencyKey = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(
                Map.of("userId", userId, "walletAccountId", walletId, "amountPaise", WITHDRAWAL_AMOUNT_PAISE));

        String firstResponseBody = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            MvcResult result = mockMvc.perform(post("/transfers/withdraw")
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();
            String responseBody = result.getResponse().getContentAsString();
            if (firstResponseBody == null) {
                firstResponseBody = responseBody;
            } else {
                assertEquals(firstResponseBody, responseBody, "attempt " + attempt + ": replayed response must be identical");
            }
        }

        long finalBalance = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, walletId);
        assertEquals(STARTING_BALANCE_PAISE - WITHDRAWAL_AMOUNT_PAISE, finalBalance,
                "money must have moved exactly once, not five times");
    }

    @Test
    void sameKeySentTenTimesInParallelStillMovesMoneyOnlyOnce() throws Exception {
        long userId = createTestUser();
        long walletId = createTestWallet(userId);
        String idempotencyKey = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(
                Map.of("userId", userId, "walletAccountId", walletId, "amountPaise", WITHDRAWAL_AMOUNT_PAISE));

        int requests = 10;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(requests);
        for (int i = 0; i < requests; i++) {
            pool.submit(() -> {
                try {
                    mockMvc.perform(post("/transfers/withdraw")
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body));
                } catch (Exception ignored) {
                    // Failures are checked via final balance below, not individual responses.
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        long finalBalance = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, walletId);
        assertEquals(STARTING_BALANCE_PAISE - WITHDRAWAL_AMOUNT_PAISE, finalBalance,
                "ten parallel requests with the same key must still move money only once");
    }

    @Test
    void sameKeyDifferentBodyIsRejectedAndNoMoneyMoves() throws Exception {
        long userId = createTestUser();
        long walletId = createTestWallet(userId);
        String idempotencyKey = UUID.randomUUID().toString();
        String firstBody = objectMapper.writeValueAsString(
                Map.of("userId", userId, "walletAccountId", walletId, "amountPaise", WITHDRAWAL_AMOUNT_PAISE));
        String differentBody = objectMapper.writeValueAsString(
                Map.of("userId", userId, "walletAccountId", walletId, "amountPaise", WITHDRAWAL_AMOUNT_PAISE * 2));

        mockMvc.perform(post("/transfers/withdraw")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(firstBody));

        MvcResult secondResult = mockMvc.perform(post("/transfers/withdraw")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(differentBody))
                .andReturn();

        assertEquals(422, secondResult.getResponse().getStatus());

        long finalBalance = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, walletId);
        assertEquals(STARTING_BALANCE_PAISE - WITHDRAWAL_AMOUNT_PAISE, finalBalance,
                "only the first request's money should have moved");
    }

    @Test
    void missingIdempotencyKeyHeaderIsRejectedWith400() throws Exception {
        long userId = createTestUser();
        long walletId = createTestWallet(userId);
        String body = objectMapper.writeValueAsString(
                Map.of("userId", userId, "walletAccountId", walletId, "amountPaise", WITHDRAWAL_AMOUNT_PAISE));

        MvcResult result = mockMvc.perform(post("/transfers/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void malformedIdempotencyKeyHeaderIsRejectedWith400() throws Exception {
        long userId = createTestUser();
        long walletId = createTestWallet(userId);
        String body = objectMapper.writeValueAsString(
                Map.of("userId", userId, "walletAccountId", walletId, "amountPaise", WITHDRAWAL_AMOUNT_PAISE));

        MvcResult result = mockMvc.perform(post("/transfers/withdraw")
                        .header("Idempotency-Key", "not-a-real-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("IDEMPOTENCY_KEY_INVALID"));

        long finalBalance = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, walletId);
        assertEquals(STARTING_BALANCE_PAISE, finalBalance, "no money should move when the key is rejected");
    }

    private long createTestUser() {
        String uniqueSuffix = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone, kyc_status) VALUES ('Idempotency Tester', ?, ?, 'verified') RETURNING id",
                Long.class, "idempotency-" + uniqueSuffix + "@example.com", "9" + System.nanoTime() % 1_000_000_000L);
    }

    private long createTestWallet(long userId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_type, name, balance) VALUES (?, 'user_wallet', 'Idempotency Test Wallet', ?) RETURNING id",
                Long.class, userId, STARTING_BALANCE_PAISE);
    }
}
