package com.wallet.reconciliation;

import com.wallet.fraud.FraudExplanationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the reconciliation job every night at 2 AM. */
@Component
public class ReconciliationScheduler {

    private final ReconciliationService reconciliationService;
    private final FraudExplanationService fraudExplanationService;

    public ReconciliationScheduler(ReconciliationService reconciliationService,
                                    FraudExplanationService fraudExplanationService) {
        this.reconciliationService = reconciliationService;
        this.fraudExplanationService = fraudExplanationService;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void runNightly() {
        reconciliationService.run();
        // Milestone 5, Part B: explain today's fraud flags after reconciliation finishes.
        fraudExplanationService.explainTodaysFlags();
    }
}
