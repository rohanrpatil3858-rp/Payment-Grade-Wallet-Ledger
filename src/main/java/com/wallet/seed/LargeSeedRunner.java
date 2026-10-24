package com.wallet.seed;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Loads the submission-scale dataset (1,000 users, 2,000 wallets, 100,000 transfers) and then
 * exits. Disabled by default - it runs ONLY when started with `--app.seed.large=true`, so it
 * never fires during normal application startup or tests.
 *
 * Run it with:
 *   ./gradlew bootRun --args="--app.seed.large=true"
 * or against Docker:
 *   docker compose exec app java -jar app.jar --app.seed.large=true
 */
@Component
@ConditionalOnProperty(name = "app.seed.large", havingValue = "true")
public class LargeSeedRunner implements CommandLineRunner {

    private final LedgerSeeder ledgerSeeder;

    public LargeSeedRunner(LedgerSeeder ledgerSeeder) {
        this.ledgerSeeder = ledgerSeeder;
    }

    @Override
    public void run(String... args) {
        System.out.println("Seeding 1,000 users / 2,000 wallets / 100,000 transfers - this may take a minute...");
        long start = System.currentTimeMillis();
        ledgerSeeder.seedLargeDataset(1_000, 2, 100_000);
        long seconds = (System.currentTimeMillis() - start) / 1000;
        System.out.println("Seed complete in " + seconds + "s. You can stop the app now.");
    }
}
