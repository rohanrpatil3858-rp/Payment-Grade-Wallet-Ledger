package com.wallet.transfer;

import com.wallet.common.ApiError;
import com.wallet.idempotency.IdempotencyService;
import com.wallet.idempotency.IdempotentOutcome;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;
    private final IdempotencyService idempotencyService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TransferController(TransferService transferService, IdempotencyService idempotencyService,
                               JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.transferService = transferService;
        this.idempotencyService = idempotencyService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/topup", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> topUp(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody TopUpRequest request) throws Exception {
        ResponseEntity<String> headerError = validateIdempotencyKey(idempotencyKey);
        if (headerError != null) {
            return headerError;
        }
        return idempotencyService.execute(request.userId(), idempotencyKey, objectMapper.writeValueAsString(request), () -> {
            long cashInAccountId = findOrCreateSystemAccount("system_cash_in", "Cash-In Account");
            TransferResult result = transferService.topUpWithinCurrentTransaction(
                    request.walletAccountId(), cashInAccountId, request.amountPaise());
            return successOutcome(result);
        });
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> sendMoney(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PeerTransferRequest request) throws Exception {
        ResponseEntity<String> headerError = validateIdempotencyKey(idempotencyKey);
        if (headerError != null) {
            return headerError;
        }
        return idempotencyService.execute(request.userId(), idempotencyKey, objectMapper.writeValueAsString(request), () -> {
            PeerTransferResult result = transferService.sendMoneyWithinCurrentTransaction(
                    request.senderAccountId(), request.recipientAccountId(), request.amountPaise(), request.note());
            return peerOutcome(result);
        });
    }

    @PostMapping(value = "/withdraw", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> withdraw(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody WithdrawRequest request) throws Exception {
        ResponseEntity<String> headerError = validateIdempotencyKey(idempotencyKey);
        if (headerError != null) {
            return headerError;
        }
        return idempotencyService.execute(request.userId(), idempotencyKey, objectMapper.writeValueAsString(request), () -> {
            long cashOutAccountId = findOrCreateSystemAccount("system_cash_out", "Cash-Out Account");
            TransferResult result = transferService.withdrawWithinCurrentTransaction(
                    request.walletAccountId(), cashOutAccountId, request.amountPaise());
            return successOutcome(result);
        });
    }

    @PostMapping(value = "/{id}/reverse", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> reverse(
            @PathVariable("id") long transferId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> body) throws Exception {
        ResponseEntity<String> headerError = validateIdempotencyKey(idempotencyKey);
        if (headerError != null) {
            return headerError;
        }
        long userId = ((Number) body.get("userId")).longValue();
        return idempotencyService.execute(userId, idempotencyKey,
                objectMapper.writeValueAsString(Map.of("transferId", transferId)), () -> {
            TransferResult result = transferService.reverseCompletedTransfer(transferId);
            return successOutcome(result);
        });
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getTransfer(@PathVariable("id") long transferId) {
        Map<String, Object> transfer = jdbcTemplate.queryForMap(
                "SELECT id, type, status, amount, note, reversal_of_transfer_id, created_at FROM transfers WHERE id = ?",
                transferId);
        List<Map<String, Object>> entries = jdbcTemplate.queryForList(
                "SELECT id, account_id, direction, amount, created_at FROM ledger_entries WHERE transfer_id = ? ORDER BY id",
                transferId);
        List<Map<String, Object>> statusHistory = jdbcTemplate.queryForList(
                "SELECT old_status, new_status, changed_by_actor, changed_at FROM transfer_status_audit_log "
                        + "WHERE transfer_id = ? ORDER BY changed_at",
                transferId);
        return ResponseEntity.ok(Map.of("transfer", transfer, "entries", entries, "statusHistory", statusHistory));
    }

    private IdempotentOutcome successOutcome(TransferResult result) {
        try {
            if (result.success()) {
                return new IdempotentOutcome(HttpStatus.CREATED.value(),
                        objectMapper.writeValueAsString(Map.of("transferId", result.transferId())));
            }
            ApiError error = new ApiError(result.errorCode(), "Insufficient funds", UUID.randomUUID().toString());
            return new IdempotentOutcome(422, objectMapper.writeValueAsString(error));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private IdempotentOutcome peerOutcome(PeerTransferResult result) {
        try {
            return switch (result.outcome()) {
                case COMPLETED -> new IdempotentOutcome(HttpStatus.CREATED.value(),
                        objectMapper.writeValueAsString(Map.of("transferId", result.transferId(), "status", "completed")));
                case HELD -> new IdempotentOutcome(HttpStatus.ACCEPTED.value(),
                        objectMapper.writeValueAsString(Map.of("transferId", result.transferId(), "status", "held")));
                case INSUFFICIENT_FUNDS -> new IdempotentOutcome(422,
                        objectMapper.writeValueAsString(new ApiError("INSUFFICIENT_FUNDS", "Insufficient funds", UUID.randomUUID().toString())));
            };
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Returns an error response if the header is missing/malformed, otherwise null. */
    private ResponseEntity<String> validateIdempotencyKey(String idempotencyKey) throws Exception {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header is required");
        }
        try {
            UUID.fromString(idempotencyKey);
        } catch (IllegalArgumentException notAUuid) {
            return errorResponse(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key must be a UUID");
        }
        return null;
    }

    /** Assumes a single platform-wide system account of each type; created on first use if missing. */
    private long findOrCreateSystemAccount(String accountType, String name) {
        List<Long> existing = jdbcTemplate.queryForList(
                "SELECT id FROM accounts WHERE account_type = ? ORDER BY id LIMIT 1", Long.class, accountType);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_type, name, balance) VALUES (NULL, ?, ?, 0) RETURNING id",
                Long.class, accountType, name);
    }

    private ResponseEntity<String> errorResponse(HttpStatus httpStatus, String code, String message) throws Exception {
        ApiError error = new ApiError(code, message, UUID.randomUUID().toString());
        return ResponseEntity.status(httpStatus).body(objectMapper.writeValueAsString(error));
    }
}
