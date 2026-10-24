package com.wallet.reconciliation;

/** Outcome of one reconciliation run attempt. */
public record ReconciliationRunResult(boolean ranSuccessfully, boolean skippedAlreadyRunning, Long runId, int issuesFound) {

    public static ReconciliationRunResult completed(long runId, int issuesFound) {
        return new ReconciliationRunResult(true, false, runId, issuesFound);
    }

    public static ReconciliationRunResult skippedBecauseAlreadyRunning() {
        return new ReconciliationRunResult(false, true, null, 0);
    }
}
