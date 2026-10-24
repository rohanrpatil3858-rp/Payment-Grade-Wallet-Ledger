package com.wallet.fraud;

import com.wallet.llm.FixedResponseLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Milestone 5, Part B: the nightly job explains today's flagged transfers via the LLM. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FraudExplanationServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void explainsAFlaggedTransferAndSavesTheLabel() {
        // Other tests may leave their own unlabeled risk_flags rows behind in this shared dev
        // database; since the job scans "today's" flags globally by design, give this test a
        // clean slate so it only sees the one flag it creates.
        jdbcTemplate.execute("TRUNCATE TABLE risk_flags");

        long sender = createWallet(2_000_000);
        long recipient = createWallet(0);
        long transferId = jdbcTemplate.queryForObject(
                "INSERT INTO transfers (type, status, amount, sender_account_id, recipient_account_id) "
                        + "VALUES ('peer_transfer', 'completed', 1000100, ?, ?) RETURNING id",
                Long.class, sender, recipient);
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'debit', 1000100)",
                transferId, sender);
        long flagId = jdbcTemplate.queryForObject(
                "INSERT INTO risk_flags (transfer_id, rule_name) VALUES (?, 'new_payee_large_amount') RETURNING id",
                Long.class, transferId);

        String fixedLlmResponse = "{\"label\": \"review\", \"explanation\": \"First large transfer to a new payee. Worth a human look.\"}";
        FraudExplanationService service = new FraudExplanationService(
                jdbcTemplate, new FixedResponseLlmClient(fixedLlmResponse), objectMapper);

        int explainedCount = service.explainTodaysFlags();

        assertEquals(1, explainedCount);
        Map<String, Object> savedFlag = jdbcTemplate.queryForMap(
                "SELECT label, explanation FROM risk_flags WHERE id = ?", flagId);
        assertEquals("review", savedFlag.get("label"));
        assertEquals("First large transfer to a new payee. Worth a human look.", savedFlag.get("explanation"));
    }

    private long createWallet(long startingBalancePaise) {
        String uniqueSuffix = UUID.randomUUID().toString();
        long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone, kyc_status) VALUES ('Explain Test User', ?, ?, 'verified') RETURNING id",
                Long.class, "explain-" + uniqueSuffix + "@example.com", "9" + System.nanoTime() % 1_000_000_000L);
        return jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_type, name, balance) VALUES (?, 'user_wallet', 'Explain Test Wallet', ?) RETURNING id",
                Long.class, userId, startingBalancePaise);
    }
}
