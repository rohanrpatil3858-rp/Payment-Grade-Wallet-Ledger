package com.wallet.fraud;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Milestone 5, Part A: each rule is a plain SQL query, run inside the transfer's own transaction
 * before commit. Thresholds live in risk_rules, not in code, so they can be tuned without a
 * deploy. Every query here reuses indexes already created in Milestone 1
 * (idx_ledger_entries_account_statement for the per-account time-window queries, and
 * idx_ledger_entries_transfer_id for the new-payee pair lookup) - no new indexes were needed.
 */
@Service
public class FraudRuleService {

    private final JdbcTemplate jdbcTemplate;

    public FraudRuleService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<FraudVerdict> evaluate(long senderAccountId, long recipientAccountId, long amountPaise) {
        List<FraudVerdict> verdicts = new ArrayList<>();
        checkVelocity(senderAccountId).ifPresent(verdicts::add);
        checkAmountSpike(senderAccountId, amountPaise).ifPresent(verdicts::add);
        checkNewPayeeLargeAmount(senderAccountId, recipientAccountId, amountPaise).ifPresent(verdicts::add);
        return verdicts;
    }

    /** Index used: idx_ledger_entries_account_statement (account_id, created_at DESC, id DESC). */
    private Optional<FraudVerdict> checkVelocity(long senderAccountId) {
        Map<String, Object> rule = jdbcTemplate.queryForMap(
                "SELECT velocity_max_count, velocity_window_minutes FROM risk_rules WHERE rule_name = 'velocity'");
        int maxCount = ((Number) rule.get("velocity_max_count")).intValue();
        int windowMinutes = ((Number) rule.get("velocity_window_minutes")).intValue();

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE account_id = ? AND direction = 'debit' "
                        + "AND created_at > now() - make_interval(mins => ?)",
                Integer.class, senderAccountId, windowMinutes);

        if (count != null && count > maxCount) {
            return Optional.of(FraudVerdict.hold("velocity"));
        }
        return Optional.empty();
    }

    /** Index used: idx_ledger_entries_account_statement (account_id, created_at DESC, id DESC). */
    private Optional<FraudVerdict> checkAmountSpike(long senderAccountId, long amountPaise) {
        Map<String, Object> rule = jdbcTemplate.queryForMap(
                "SELECT amount_spike_multiplier FROM risk_rules WHERE rule_name = 'amount_spike'");
        BigDecimal multiplier = (BigDecimal) rule.get("amount_spike_multiplier");

        Double averageAmount = jdbcTemplate.queryForObject(
                "SELECT AVG(amount) FROM ledger_entries WHERE account_id = ? AND direction = 'debit' "
                        + "AND created_at > now() - interval '30 days'",
                Double.class, senderAccountId);

        // No 30-day history yet - nothing to compare against, so this rule can't fire.
        if (averageAmount == null) {
            return Optional.empty();
        }
        if (amountPaise > averageAmount * multiplier.doubleValue()) {
            return Optional.of(FraudVerdict.hold("amount_spike"));
        }
        return Optional.empty();
    }

    /** Index used: idx_ledger_entries_transfer_id (for the self-join on transfer_id). */
    private Optional<FraudVerdict> checkNewPayeeLargeAmount(long senderAccountId, long recipientAccountId, long amountPaise) {
        Map<String, Object> rule = jdbcTemplate.queryForMap(
                "SELECT new_payee_min_amount_paise FROM risk_rules WHERE rule_name = 'new_payee_large_amount'");
        long minAmountPaise = ((Number) rule.get("new_payee_min_amount_paise")).longValue();

        if (amountPaise <= minAmountPaise) {
            return Optional.empty();
        }

        Boolean hasSentToThisPayeeBefore = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM ledger_entries d JOIN ledger_entries c ON d.transfer_id = c.transfer_id "
                        + "WHERE d.account_id = ? AND d.direction = 'debit' AND c.account_id = ? AND c.direction = 'credit')",
                Boolean.class, senderAccountId, recipientAccountId);

        if (Boolean.FALSE.equals(hasSentToThisPayeeBefore)) {
            return Optional.of(FraudVerdict.flagAllow("new_payee_large_amount"));
        }
        return Optional.empty();
    }
}
