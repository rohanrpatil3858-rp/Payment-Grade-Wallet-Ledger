package com.wallet.idempotency;

import com.wallet.common.ApiError;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Milestone 3: makes a write request idempotent. The (user_id, idempotency_key) unique
 * constraint on idempotency_keys does the hard part - if two identical requests race, Postgres
 * lets only one INSERT succeed, and the loser falls into the catch block below.
 */
@Service
public class IdempotencyService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
                               ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * Inserts the idempotency key and runs businessLogic inside one transaction, then saves the
     * result on the key row and commits. If the key already exists, the insert fails, the
     * transaction rolls back, and we decide the response from the existing row instead.
     */
    public ResponseEntity<String> execute(long userId, String idempotencyKey, String requestBodyJson,
                                           Supplier<IdempotentOutcome> businessLogic) {
        String requestHash = sha256(requestBodyJson);

        try {
            IdempotentOutcome outcome = transactionTemplate.execute(status -> {
                jdbcTemplate.update(
                        "INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash, status) VALUES (?, ?, ?, 'in_progress')",
                        userId, idempotencyKey, requestHash);

                IdempotentOutcome result = businessLogic.get();

                jdbcTemplate.update(
                        "UPDATE idempotency_keys SET status = 'completed', response_status_code = ?, response_body = ? "
                                + "WHERE user_id = ? AND idempotency_key = ?",
                        result.httpStatus(), result.bodyJson(), userId, idempotencyKey);

                return result;
            });
            return ResponseEntity.status(outcome.httpStatus()).body(outcome.bodyJson());
        } catch (DuplicateKeyException alreadySeenKey) {
            return respondFromExistingKey(userId, idempotencyKey, requestHash);
        }
    }

    private ResponseEntity<String> respondFromExistingKey(long userId, String idempotencyKey, String requestHash) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT request_hash, status, response_status_code, response_body FROM idempotency_keys "
                        + "WHERE user_id = ? AND idempotency_key = ?",
                userId, idempotencyKey);
        if (rows.isEmpty()) {
            return errorResponse(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_IN_PROGRESS", "Please retry shortly");
        }

        Map<String, Object> row = rows.get(0);
        String existingHash = (String) row.get("request_hash");
        String status = (String) row.get("status");

        if (!existingHash.equals(requestHash)) {
            return errorResponse(HttpStatus.valueOf(422), "IDEMPOTENCY_KEY_REUSED",
                    "This idempotency key was already used with a different request body");
        }
        if ("completed".equals(status)) {
            Integer savedStatusCode = (Integer) row.get("response_status_code");
            String savedBody = row.get("response_body").toString();
            return ResponseEntity.status(savedStatusCode).body(savedBody);
        }
        // status == 'in_progress': another request with this exact key is still being handled.
        return errorResponse(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_IN_PROGRESS", "Please retry shortly");
    }

    private ResponseEntity<String> errorResponse(HttpStatus httpStatus, String code, String message) {
        ApiError error = new ApiError(code, message, UUID.randomUUID().toString());
        try {
            return ResponseEntity.status(httpStatus).body(objectMapper.writeValueAsString(error));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
