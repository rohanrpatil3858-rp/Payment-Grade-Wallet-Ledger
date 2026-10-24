package com.wallet.transfer;

import com.wallet.fraud.FraudRuleService;
import com.wallet.fraud.FraudVerdict;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * Milestone 2, Step 3: a withdrawal that is safe under concurrent load.
 *
 * The money path is kept explicit on purpose (plain SQL via JdbcTemplate, visible transaction
 * boundary via TransactionTemplate) rather than hidden behind ORM magic, per the brief.
 *
 * Transfer transaction runs at the database's default Read Committed isolation level.
 * Read Committed alone would allow a lost update (two concurrent withdrawals could both read
 * the same balance before either writes). SELECT ... FOR UPDATE closes that gap directly: it
 * takes an exclusive row lock at read time, so a second transaction asking for the same row
 * simply waits until the first commits, then re-reads the now-current balance. This gives us
 * the correctness we need without paying Serializable's cost of forcing failed transactions to
 * retry from scratch under contention - Serializable would be unnecessary overhead here since
 * every withdrawal already touches the exact same two rows in a fixed order.
 */
@Service
public class TransferService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final FraudRuleService fraudRuleService;

    public TransferService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
                            FraudRuleService fraudRuleService) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.fraudRuleService = fraudRuleService;
    }

    /** Moves money out of a user wallet into a system account (e.g. withdrawal to cash-out). */
    public TransferResult withdraw(long walletAccountId, long systemAccountId, long amountPaise) {
        return transactionTemplate.execute(status ->
                withdrawWithinCurrentTransaction(walletAccountId, systemAccountId, amountPaise));
    }

    /**
     * Same withdrawal logic, but runs inside a transaction the caller has already opened -
     * used by Milestone 3's idempotency wrapper, which must insert the idempotency key row
     * and run the transfer in one single transaction. Must only be called from inside an
     * existing Spring-managed transaction.
     */
    public TransferResult withdrawWithinCurrentTransaction(long walletAccountId, long systemAccountId, long amountPaise) {
        // Always lock rows in ascending account_id order so two concurrent transfers
        // can never lock A-then-B while the other locks B-then-A (which would deadlock).
        long firstId = Math.min(walletAccountId, systemAccountId);
        long secondId = Math.max(walletAccountId, systemAccountId);

        long firstBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE id = ? FOR UPDATE", Long.class, firstId);
        long secondBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE id = ? FOR UPDATE", Long.class, secondId);

        long walletBalance = (walletAccountId == firstId) ? firstBalance : secondBalance;

        // Re-checked while still holding the lock: no other transaction can change this
        // row out from under us until we commit or roll back. No rows have been written yet,
        // so simply returning here is enough - nothing needs to be undone.
        if (walletBalance < amountPaise) {
            return TransferResult.insufficientFunds();
        }

        Long transferId = jdbcTemplate.queryForObject(
                "INSERT INTO transfers (type, status, amount) VALUES ('withdrawal', 'completed', ?) RETURNING id",
                Long.class, amountPaise);

        jdbcTemplate.update(
                "INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'debit', ?)",
                transferId, walletAccountId, amountPaise);
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'credit', ?)",
                transferId, systemAccountId, amountPaise);

        jdbcTemplate.update(
                "UPDATE accounts SET balance = balance - ? WHERE id = ?", amountPaise, walletAccountId);
        jdbcTemplate.update(
                "UPDATE accounts SET balance = balance + ? WHERE id = ?", amountPaise, systemAccountId);

        return TransferResult.success(transferId);
    }

    /** Standalone entry point for peer-to-peer transfers: opens and manages its own transaction. */
    public PeerTransferResult sendMoney(long senderAccountId, long recipientAccountId, long amountPaise, String note) {
        return transactionTemplate.execute(status ->
                sendMoneyWithinCurrentTransaction(senderAccountId, recipientAccountId, amountPaise, note));
    }

    /**
     * Milestone 5, Part A: fraud rules run here, inside the same transaction as the transfer,
     * before anything commits. A HOLD verdict stops money from moving at all - no ledger entries,
     * no balance change - until an admin releases it. A FLAG_ALLOW verdict lets the transfer
     * complete normally, but records a risk_flags row for the nightly job to explain later.
     */
    public PeerTransferResult sendMoneyWithinCurrentTransaction(long senderAccountId, long recipientAccountId,
                                                                 long amountPaise, String note) {
        long firstId = Math.min(senderAccountId, recipientAccountId);
        long secondId = Math.max(senderAccountId, recipientAccountId);

        long firstBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE id = ? FOR UPDATE", Long.class, firstId);
        long secondBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE id = ? FOR UPDATE", Long.class, secondId);
        long senderBalance = (senderAccountId == firstId) ? firstBalance : secondBalance;

        if (senderBalance < amountPaise) {
            return PeerTransferResult.insufficientFunds();
        }

        List<FraudVerdict> verdicts = fraudRuleService.evaluate(senderAccountId, recipientAccountId, amountPaise);
        boolean held = verdicts.stream().anyMatch(v -> v.action() == FraudVerdict.Action.HOLD);
        String status = held ? "held" : "completed";

        Long transferId = jdbcTemplate.queryForObject(
                "INSERT INTO transfers (type, status, amount, note, sender_account_id, recipient_account_id) "
                        + "VALUES ('peer_transfer', ?, ?, ?, ?, ?) RETURNING id",
                Long.class, status, amountPaise, note, senderAccountId, recipientAccountId);

        for (FraudVerdict verdict : verdicts) {
            if (verdict.action() == FraudVerdict.Action.FLAG_ALLOW) {
                jdbcTemplate.update(
                        "INSERT INTO risk_flags (transfer_id, rule_name) VALUES (?, ?)", transferId, verdict.ruleName());
            }
        }

        if (held) {
            // Money has not moved: no ledger entries, no balance change, until an admin releases it.
            return PeerTransferResult.held(transferId);
        }

        jdbcTemplate.update(
                "INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'debit', ?)",
                transferId, senderAccountId, amountPaise);
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'credit', ?)",
                transferId, recipientAccountId, amountPaise);

        jdbcTemplate.update(
                "UPDATE accounts SET balance = balance - ? WHERE id = ?", amountPaise, senderAccountId);
        jdbcTemplate.update(
                "UPDATE accounts SET balance = balance + ? WHERE id = ?", amountPaise, recipientAccountId);

        return PeerTransferResult.completed(transferId);
    }

    /** Admin releases a held transfer: money moves now, exactly as if it had never been held. */
    public PeerTransferResult releaseHeldTransfer(long transferId) {
        return transactionTemplate.execute(status -> {
            Map<String, Object> transfer = jdbcTemplate.queryForMap(
                    "SELECT amount, sender_account_id, recipient_account_id, status FROM transfers WHERE id = ? FOR UPDATE",
                    transferId);
            if (!"held".equals(transfer.get("status"))) {
                throw new IllegalStateException("Transfer " + transferId + " is not held");
            }
            long amountPaise = ((Number) transfer.get("amount")).longValue();
            long senderAccountId = ((Number) transfer.get("sender_account_id")).longValue();
            long recipientAccountId = ((Number) transfer.get("recipient_account_id")).longValue();

            long firstId = Math.min(senderAccountId, recipientAccountId);
            long secondId = Math.max(senderAccountId, recipientAccountId);
            jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ? FOR UPDATE", Long.class, firstId);
            jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ? FOR UPDATE", Long.class, secondId);

            jdbcTemplate.update(
                    "INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'debit', ?)",
                    transferId, senderAccountId, amountPaise);
            jdbcTemplate.update(
                    "INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'credit', ?)",
                    transferId, recipientAccountId, amountPaise);
            jdbcTemplate.update("UPDATE accounts SET balance = balance - ? WHERE id = ?", amountPaise, senderAccountId);
            jdbcTemplate.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", amountPaise, recipientAccountId);
            jdbcTemplate.update("UPDATE transfers SET status = 'completed' WHERE id = ?", transferId);

            return PeerTransferResult.completed(transferId);
        });
    }

    /** Admin reverses a held transfer: since money never moved, there is nothing to undo. */
    public void reverseHeldTransfer(long transferId) {
        transactionTemplate.executeWithoutResult(status -> {
            int updated = jdbcTemplate.update(
                    "UPDATE transfers SET status = 'reversed' WHERE id = ? AND status = 'held'", transferId);
            if (updated == 0) {
                throw new IllegalStateException("Transfer " + transferId + " is not held");
            }
        });
    }

    /** Top-up: money flows from the system cash-in account (debit) into a user wallet (credit). */
    public TransferResult topUp(long walletAccountId, long cashInAccountId, long amountPaise) {
        return transactionTemplate.execute(status ->
                topUpWithinCurrentTransaction(walletAccountId, cashInAccountId, amountPaise));
    }

    public TransferResult topUpWithinCurrentTransaction(long walletAccountId, long cashInAccountId, long amountPaise) {
        // The cash-in account is a system account and may go negative, so no balance check is
        // needed on the funding side; lock both rows in ascending id order to avoid deadlocks.
        long firstId = Math.min(walletAccountId, cashInAccountId);
        long secondId = Math.max(walletAccountId, cashInAccountId);
        jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ? FOR UPDATE", Long.class, firstId);
        jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ? FOR UPDATE", Long.class, secondId);

        Long transferId = jdbcTemplate.queryForObject(
                "INSERT INTO transfers (type, status, amount, sender_account_id, recipient_account_id) "
                        + "VALUES ('top_up', 'completed', ?, ?, ?) RETURNING id",
                Long.class, amountPaise, cashInAccountId, walletAccountId);

        jdbcTemplate.update(
                "INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'debit', ?)",
                transferId, cashInAccountId, amountPaise);
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'credit', ?)",
                transferId, walletAccountId, amountPaise);
        jdbcTemplate.update("UPDATE accounts SET balance = balance - ? WHERE id = ?", amountPaise, cashInAccountId);
        jdbcTemplate.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", amountPaise, walletAccountId);

        return TransferResult.success(transferId);
    }

    /**
     * Reverses an already-completed transfer by writing a NEW reversal transfer with ledger
     * entries that exactly undo the original's - the original and its entries are never touched
     * (ledger entries are immutable). A wallet that would be pushed negative by the reversal
     * rejects it, keeping the non-negative-balance invariant intact.
     */
    public TransferResult reverseCompletedTransfer(long originalTransferId) {
        return transactionTemplate.execute(status -> {
            Map<String, Object> original = jdbcTemplate.queryForMap(
                    "SELECT amount, status FROM transfers WHERE id = ? FOR UPDATE", originalTransferId);
            if (!"completed".equals(original.get("status"))) {
                throw new IllegalStateException("Only a completed transfer can be reversed");
            }

            List<Map<String, Object>> originalEntries = jdbcTemplate.queryForList(
                    "SELECT account_id, direction, amount FROM ledger_entries WHERE transfer_id = ? ORDER BY account_id",
                    originalTransferId);

            // Lock every affected account row (ascending id order) before touching any balance.
            for (Map<String, Object> entry : originalEntries) {
                jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ? FOR UPDATE",
                        Long.class, ((Number) entry.get("account_id")).longValue());
            }

            long amountPaise = ((Number) original.get("amount")).longValue();
            Long reversalTransferId = jdbcTemplate.queryForObject(
                    "INSERT INTO transfers (type, status, amount, reversal_of_transfer_id) "
                            + "VALUES ('reversal', 'completed', ?, ?) RETURNING id",
                    Long.class, amountPaise, originalTransferId);

            for (Map<String, Object> entry : originalEntries) {
                long accountId = ((Number) entry.get("account_id")).longValue();
                long entryAmount = ((Number) entry.get("amount")).longValue();
                boolean wasDebit = "debit".equals(entry.get("direction"));
                // Flip direction: a debit is undone by a credit, and vice versa.
                String reversedDirection = wasDebit ? "credit" : "debit";
                long balanceDelta = wasDebit ? entryAmount : -entryAmount;

                if (reversedDirection.equals("debit")) {
                    Long accountBalance = jdbcTemplate.queryForObject(
                            "SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
                    String accountType = jdbcTemplate.queryForObject(
                            "SELECT account_type FROM accounts WHERE id = ?", String.class, accountId);
                    if ("user_wallet".equals(accountType) && accountBalance + balanceDelta < 0) {
                        status.setRollbackOnly();
                        return TransferResult.insufficientFunds();
                    }
                }

                jdbcTemplate.update(
                        "INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, ?, ?)",
                        reversalTransferId, accountId, reversedDirection, entryAmount);
                jdbcTemplate.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", balanceDelta, accountId);
            }

            jdbcTemplate.update("UPDATE transfers SET status = 'reversed' WHERE id = ?", originalTransferId);
            return TransferResult.success(reversalTransferId);
        });
    }
}
