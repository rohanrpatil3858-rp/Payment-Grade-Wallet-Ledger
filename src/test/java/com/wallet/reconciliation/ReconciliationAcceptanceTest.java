package com.wallet.reconciliation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.wallet.seed.LedgerSeeder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone 4 acceptance test: seed 10,000 transfers, corrupt three specific things directly in
 * the database, then the reconciliation job must report exactly those three problems and
 * nothing else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReconciliationAcceptanceTest {

    @Autowired
    private LedgerSeeder ledgerSeeder;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void reportsExactlyTheThreeSeededCorruptions() {
        // This test needs a database with no pre-existing debris from other tests (e.g. other
        // tests' wallets that were given a balance directly, without a matching ledger entry) -
        // otherwise the global checks below would also flag that unrelated old data.
        jdbcTemplate.execute(
                "TRUNCATE TABLE reconciliation_issues, reconciliation_runs, ledger_entries, transfers, idempotency_keys, accounts, users RESTART IDENTITY CASCADE");

        LedgerSeeder.SeedResult seed = ledgerSeeder.seedTopUps(200, 10_000);
        long corruptedAccountId = seed.walletAccountIds().get(0);

        // Corruption 1: change one entry's amount directly (bypassing the immutability trigger,
        // simulating direct database tampering, which is exactly what this test is for). We also
        // keep that entry's own wallet's stored balance in sync with the change, so this
        // corruption isolates check 2 (transfer balance) only, not check 3 (stored balance) too -
        // otherwise every ledger edit would also look like a stored-balance problem.
        long corruptedEntryId = jdbcTemplate.queryForObject(
                "SELECT id FROM ledger_entries WHERE direction = 'credit' AND account_id <> ? ORDER BY id LIMIT 1",
                Long.class, corruptedAccountId);
        long corruptedTransferId1 = jdbcTemplate.queryForObject(
                "SELECT transfer_id FROM ledger_entries WHERE id = ?", Long.class, corruptedEntryId);
        long corruptedEntryAccountId = jdbcTemplate.queryForObject(
                "SELECT account_id FROM ledger_entries WHERE id = ?", Long.class, corruptedEntryId);
        withLedgerEntriesTriggersDisabled(() ->
                jdbcTemplate.update("UPDATE ledger_entries SET amount = amount + 500 WHERE id = ?", corruptedEntryId));
        jdbcTemplate.update("UPDATE accounts SET balance = balance + 500 WHERE id = ?", corruptedEntryAccountId);

        // Corruption 2: delete one credit entry entirely, leaving its transfer unbalanced - and
        // again keep that entry's wallet's stored balance in sync, isolating check 2 only.
        long deletedEntryId = jdbcTemplate.queryForObject(
                "SELECT id FROM ledger_entries WHERE direction = 'credit' AND id <> ? AND account_id <> ? ORDER BY id DESC LIMIT 1",
                Long.class, corruptedEntryId, corruptedAccountId);
        long corruptedTransferId2 = jdbcTemplate.queryForObject(
                "SELECT transfer_id FROM ledger_entries WHERE id = ?", Long.class, deletedEntryId);
        long deletedEntryAccountId = jdbcTemplate.queryForObject(
                "SELECT account_id FROM ledger_entries WHERE id = ?", Long.class, deletedEntryId);
        long deletedEntryAmount = jdbcTemplate.queryForObject(
                "SELECT amount FROM ledger_entries WHERE id = ?", Long.class, deletedEntryId);
        withLedgerEntriesTriggersDisabled(() ->
                jdbcTemplate.update("DELETE FROM ledger_entries WHERE id = ?", deletedEntryId));
        jdbcTemplate.update("UPDATE accounts SET balance = balance - ? WHERE id = ?", deletedEntryAmount, deletedEntryAccountId);

        // Corruption 3: change one stored balance directly (no trigger involved - accounts.balance
        // is a normal mutable column). This is the only corruption that should surface as a
        // stored_balance issue.
        jdbcTemplate.update("UPDATE accounts SET balance = balance + 12345 WHERE id = ?", corruptedAccountId);

        ReconciliationRunResult result = reconciliationService.run();

        assertTrue(result.ranSuccessfully(), "run must not be skipped");
        assertEquals(3, result.issuesFound(), "exactly three problems must be reported");

        List<Map<String, Object>> issues = jdbcTemplate.queryForList(
                "SELECT check_name, entity_id FROM reconciliation_issues WHERE run_id = ? ORDER BY id", result.runId());
        assertEquals(3, issues.size());

        long transferBalanceIssues = issues.stream().filter(i -> "transfer_balance".equals(i.get("check_name"))).count();
        long storedBalanceIssues = issues.stream().filter(i -> "stored_balance".equals(i.get("check_name"))).count();
        assertEquals(2, transferBalanceIssues, "both corrupted transfers must be reported");
        assertEquals(1, storedBalanceIssues, "the corrupted account balance must be reported");

        List<Long> flaggedTransferIds = issues.stream()
                .filter(i -> "transfer_balance".equals(i.get("check_name")))
                .map(i -> (Long) i.get("entity_id"))
                .toList();
        assertTrue(flaggedTransferIds.contains(corruptedTransferId1));
        assertTrue(flaggedTransferIds.contains(corruptedTransferId2));

        List<Long> flaggedAccountIds = issues.stream()
                .filter(i -> "stored_balance".equals(i.get("check_name")))
                .map(i -> (Long) i.get("entity_id"))
                .toList();
        assertTrue(flaggedAccountIds.contains(corruptedAccountId));
    }

    private void withLedgerEntriesTriggersDisabled(Runnable action) {
        jdbcTemplate.execute("ALTER TABLE ledger_entries DISABLE TRIGGER ALL");
        try {
            action.run();
        } finally {
            jdbcTemplate.execute("ALTER TABLE ledger_entries ENABLE TRIGGER ALL");
        }
    }
}
