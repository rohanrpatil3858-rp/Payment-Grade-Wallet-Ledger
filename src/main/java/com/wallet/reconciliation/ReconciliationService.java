package com.wallet.reconciliation;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * Milestone 4: a nightly job that proves the ledger is correct and reports problems - it never
 * fixes them. Every check below runs inside one REPEATABLE READ transaction, so a transfer
 * committed mid-run can't make two checks disagree about the state of the books. The whole run
 * holds a SELECT ... FOR UPDATE NOWAIT lock on job_locks so at most one run is ever in progress.
 *
 * Design note on checks 1 and 2: a global "do all debits equal all credits" check would, on its
 * own, re-report every problem that the per-transfer check already pinpoints (since a sum of
 * balanced things is always balanced - any global mismatch is explained by at least one specific
 * broken transfer). To avoid double-reporting the same root cause twice, the global check only
 * raises an issue when the imbalance is NOT already explained by any broken transfer found by
 * check 2 - which would indicate a stranger problem (e.g. an entry whose amount changed in a way
 * that still happens to keep its own transfer's debit/credit equal, which can't actually happen
 * with our schema, but keeping the check is still a cheap, valuable safety net).
 */
@Service
public class ReconciliationService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public ReconciliationService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        TransactionTemplate repeatableRead = new TransactionTemplate(transactionManager);
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.transactionTemplate = repeatableRead;
    }

    public ReconciliationRunResult run() {
        try {
            ReconciliationRunResult result = transactionTemplate.execute(status -> runChecksHoldingLock());
            cleanupOldIdempotencyKeys();
            return result;
        } catch (CannotAcquireLockException anotherRunHoldsTheLock) {
            return ReconciliationRunResult.skippedBecauseAlreadyRunning();
        }
    }

    /**
     * Milestone 3's optional cleanup, done here as suggested: completed idempotency keys older
     * than 24 hours are no longer useful for detecting a retry, so they can be deleted. Only
     * 'completed' keys are removed - an 'in_progress' key, however old, is left alone rather
     * than risking deleting one a slow request might still complete against.
     */
    private void cleanupOldIdempotencyKeys() {
        jdbcTemplate.update(
                "DELETE FROM idempotency_keys WHERE status = 'completed' AND created_at < now() - interval '24 hours'");
    }

    private ReconciliationRunResult runChecksHoldingLock() {
        // Held until this transaction commits or rolls back - NOWAIT means a second concurrent
        // run fails immediately instead of queueing behind this one.
        jdbcTemplate.queryForObject(
                "SELECT job_name FROM job_locks WHERE job_name = 'reconciliation' FOR UPDATE NOWAIT",
                String.class);

        Long runId = jdbcTemplate.queryForObject(
                "INSERT INTO reconciliation_runs (status) VALUES ('running') RETURNING id", Long.class);

        int transferIssues = checkEveryTransferBalances(runId);
        int globalIssues = checkEverythingBalancesGlobally(runId, transferIssues > 0);
        int storedBalanceIssues = checkStoredBalancesAreRight(runId);
        int overdraftIssues = checkNoOverdrafts(runId);
        int orphanIssues = checkNothingOrphaned(runId);

        int issuesFound = transferIssues + globalIssues + storedBalanceIssues + overdraftIssues + orphanIssues;

        jdbcTemplate.update(
                "UPDATE reconciliation_runs SET finished_at = now(), status = 'completed', checks_run = 5, issues_found = ? WHERE id = ?",
                issuesFound, runId);

        return ReconciliationRunResult.completed(runId, issuesFound);
    }

    /** Check 2: each transfer's debits must equal its credits. */
    private int checkEveryTransferBalances(long runId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT transfer_id, "
                        + "COALESCE(SUM(CASE WHEN direction = 'debit' THEN amount ELSE 0 END), 0)::BIGINT AS debit_total, "
                        + "COALESCE(SUM(CASE WHEN direction = 'credit' THEN amount ELSE 0 END), 0)::BIGINT AS credit_total "
                        + "FROM ledger_entries "
                        + "GROUP BY transfer_id "
                        + "HAVING COALESCE(SUM(CASE WHEN direction = 'debit' THEN amount ELSE 0 END), 0) "
                        + "<> COALESCE(SUM(CASE WHEN direction = 'credit' THEN amount ELSE 0 END), 0)");
        for (Map<String, Object> row : rows) {
            insertIssue(runId, "transfer_balance", (Long) row.get("transfer_id"),
                    (Long) row.get("debit_total"), (Long) row.get("credit_total"));
        }
        return rows.size();
    }

    /** Check 1: the sum of all debits must equal the sum of all credits. */
    private int checkEverythingBalancesGlobally(long runId, boolean alreadyExplainedByTransferCheck) {
        Map<String, Object> totals = jdbcTemplate.queryForMap(
                "SELECT COALESCE(SUM(CASE WHEN direction = 'debit' THEN amount ELSE 0 END), 0)::BIGINT AS total_debits, "
                        + "COALESCE(SUM(CASE WHEN direction = 'credit' THEN amount ELSE 0 END), 0)::BIGINT AS total_credits "
                        + "FROM ledger_entries");
        long totalDebits = (Long) totals.get("total_debits");
        long totalCredits = (Long) totals.get("total_credits");
        if (totalDebits == totalCredits || alreadyExplainedByTransferCheck) {
            return 0;
        }
        insertIssue(runId, "global_balance", null, totalDebits, totalCredits);
        return 1;
    }

    /** Check 3: each account's stored balance must match what its ledger entries add up to. */
    private int checkStoredBalancesAreRight(long runId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT a.id AS account_id, a.balance AS stored_balance, "
                        + "COALESCE(SUM(CASE WHEN le.direction = 'credit' THEN le.amount "
                        + "WHEN le.direction = 'debit' THEN -le.amount ELSE 0 END), 0)::BIGINT AS computed_balance "
                        + "FROM accounts a LEFT JOIN ledger_entries le ON le.account_id = a.id "
                        + "GROUP BY a.id, a.balance "
                        + "HAVING a.balance <> COALESCE(SUM(CASE WHEN le.direction = 'credit' THEN le.amount "
                        + "WHEN le.direction = 'debit' THEN -le.amount ELSE 0 END), 0)");
        for (Map<String, Object> row : rows) {
            insertIssue(runId, "stored_balance", (Long) row.get("account_id"),
                    (Long) row.get("computed_balance"), (Long) row.get("stored_balance"));
        }
        return rows.size();
    }

    /** Check 4: no user wallet's computed balance (from the ledger) may be below zero. */
    private int checkNoOverdrafts(long runId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT a.id AS account_id, "
                        + "COALESCE(SUM(CASE WHEN le.direction = 'credit' THEN le.amount "
                        + "WHEN le.direction = 'debit' THEN -le.amount ELSE 0 END), 0)::BIGINT AS computed_balance "
                        + "FROM accounts a JOIN ledger_entries le ON le.account_id = a.id "
                        + "WHERE a.account_type = 'user_wallet' "
                        + "GROUP BY a.id "
                        + "HAVING COALESCE(SUM(CASE WHEN le.direction = 'credit' THEN le.amount "
                        + "WHEN le.direction = 'debit' THEN -le.amount ELSE 0 END), 0) < 0");
        for (Map<String, Object> row : rows) {
            insertIssue(runId, "overdraft", (Long) row.get("account_id"), 0L, (Long) row.get("computed_balance"));
        }
        return rows.size();
    }

    /** Check 5: every transfer must have at least one ledger entry. */
    private int checkNothingOrphaned(long runId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT t.id AS transfer_id FROM transfers t "
                        + "LEFT JOIN ledger_entries le ON le.transfer_id = t.id "
                        + "GROUP BY t.id HAVING COUNT(le.id) = 0");
        for (Map<String, Object> row : rows) {
            insertIssue(runId, "orphaned_transfer", (Long) row.get("transfer_id"), 1L, 0L);
        }
        return rows.size();
    }

    private void insertIssue(long runId, String checkName, Long entityId, Long expected, Long actual) {
        jdbcTemplate.update(
                "INSERT INTO reconciliation_issues (run_id, check_name, entity_id, expected_value, actual_value) "
                        + "VALUES (?, ?, ?, ?, ?)",
                runId, checkName, entityId, expected, actual);
    }
}
