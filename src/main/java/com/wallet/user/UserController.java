package com.wallet.user;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class UserController {

    private final JdbcTemplate jdbcTemplate;

    public UserController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, String> body) {
        long id = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone, kyc_status) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, body.get("name"), body.get("email"), body.get("phone"),
                body.getOrDefault("kycStatus", "pending"));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("userId", id));
    }

    @PostMapping("/users/{id}/wallets")
    public ResponseEntity<Map<String, Object>> openWallet(@PathVariable("id") long userId,
                                                           @RequestBody Map<String, Object> body) {
        String name = (String) body.getOrDefault("name", "Main Wallet");
        long id = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_type, name, balance) VALUES (?, 'user_wallet', ?, 0) RETURNING id",
                Long.class, userId, name);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("walletId", id));
    }
}
