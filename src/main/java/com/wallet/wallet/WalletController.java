package com.wallet.wallet;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class WalletController {

    private final JdbcTemplate jdbcTemplate;

    public WalletController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/wallets/{id}/balance")
    public ResponseEntity<Map<String, Object>> balance(@PathVariable("id") long walletId) {
        long balance = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, walletId);
        return ResponseEntity.ok(Map.of("walletId", walletId, "balancePaise", balance));
    }

    /**
     * Paginated statement, newest first. Uses the (account_id, created_at DESC, id DESC) index
     * from Milestone 1. OFFSET-based pagination is simple but gets slower at large offsets (the
     * database must still scan and discard every skipped row) - noted in docs/schema.md.
     */
    @GetMapping("/wallets/{id}/statement")
    public ResponseEntity<Map<String, Object>> statement(
            @PathVariable("id") long walletId,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        List<Map<String, Object>> entries = jdbcTemplate.queryForList(
                "SELECT le.id, le.transfer_id, le.direction, le.amount, le.created_at, t.type "
                        + "FROM ledger_entries le JOIN transfers t ON t.id = le.transfer_id "
                        + "WHERE le.account_id = ? ORDER BY le.created_at DESC, le.id DESC LIMIT ? OFFSET ?",
                walletId, limit, offset);
        return ResponseEntity.ok(Map.of("walletId", walletId, "limit", limit, "offset", offset, "entries", entries));
    }
}
