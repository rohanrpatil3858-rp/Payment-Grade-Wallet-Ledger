package com.wallet.fraud;

import com.wallet.transfer.PeerTransferResult;
import com.wallet.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Milestone 5, Part A: each fraud rule, tested in isolation with its own fresh wallets. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FraudRuleAcceptanceTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void velocityRuleHoldsAfterFiveOutgoingTransfersInTenMinutes() {
        long sender = createWallet(1_000_000);
        long recipient = createWallet(0);

        PeerTransferResult lastResult = null;
        for (int i = 0; i < 7; i++) {
            lastResult = transferService.sendMoney(sender, recipient, 1000, "payment " + i);
        }

        assertEquals(PeerTransferResult.Outcome.HELD, lastResult.outcome(), "7th transfer in under 10 minutes must be held");
    }

    @Test
    void amountSpikeRuleHoldsWhenOverFiveTimesThirtyDayAverage() {
        long sender = createWallet(1_000_000);
        long recipient = createWallet(0);

        // Build a 30-day average of Rs 10 (1000 paise) with 3 normal transfers.
        for (int i = 0; i < 3; i++) {
            transferService.sendMoney(sender, recipient, 1000, "normal " + i);
        }

        // Rs 100 (10000 paise) is 10x the Rs 10 average - over the 5x threshold.
        PeerTransferResult spikeResult = transferService.sendMoney(sender, recipient, 10000, "big one");

        assertEquals(PeerTransferResult.Outcome.HELD, spikeResult.outcome(), "amount over 5x average must be held");
    }

    @Test
    void newPayeeLargeAmountRuleFlagsButAllowsTheTransfer() {
        long sender = createWallet(2_000_000);
        long recipient = createWallet(0);

        // Rs 10,001 to a payee never sent to before - over the Rs 10,000 threshold.
        PeerTransferResult result = transferService.sendMoney(sender, recipient, 1000100, "first payment");

        assertEquals(PeerTransferResult.Outcome.COMPLETED, result.outcome(), "new payee rule only flags, it does not hold");

        Integer flagCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM risk_flags WHERE transfer_id = ? AND rule_name = 'new_payee_large_amount'",
                Integer.class, result.transferId());
        assertEquals(1, flagCount, "a risk_flags row must be recorded for the nightly job to explain");
    }

    @Test
    void secondTransferToSamePayeeDoesNotFlagAgain() {
        long sender = createWallet(2_000_000);
        long recipient = createWallet(0);

        transferService.sendMoney(sender, recipient, 1000100, "first payment");
        PeerTransferResult second = transferService.sendMoney(sender, recipient, 1000100, "second payment");

        Integer flagCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM risk_flags WHERE transfer_id = ?", Integer.class, second.transferId());
        assertEquals(0, flagCount, "the payee is no longer new, so the rule must not fire again");
    }

    @Test
    void adminCanReleaseAHeldTransferAndMoneyThenMoves() {
        long sender = createWallet(1_000_000);
        long recipient = createWallet(0);

        PeerTransferResult lastResult = null;
        for (int i = 0; i < 7; i++) {
            lastResult = transferService.sendMoney(sender, recipient, 1000, "payment " + i);
        }
        assertEquals(PeerTransferResult.Outcome.HELD, lastResult.outcome());

        long balanceBeforeRelease = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, recipient);
        transferService.releaseHeldTransfer(lastResult.transferId());
        long balanceAfterRelease = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, recipient);

        assertTrue(balanceAfterRelease > balanceBeforeRelease, "releasing a held transfer must move the money");
    }

    private long createWallet(long startingBalancePaise) {
        String uniqueSuffix = UUID.randomUUID().toString();
        long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone, kyc_status) VALUES ('Fraud Test User', ?, ?, 'verified') RETURNING id",
                Long.class, "fraud-" + uniqueSuffix + "@example.com", "9" + System.nanoTime() % 1_000_000_000L);
        return jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_type, name, balance) VALUES (?, 'user_wallet', 'Fraud Test Wallet', ?) RETURNING id",
                Long.class, userId, startingBalancePaise);
    }
}
