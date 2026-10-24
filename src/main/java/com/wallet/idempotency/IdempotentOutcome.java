package com.wallet.idempotency;

/** What a controller's business logic hands back to be saved on the idempotency key row. */
public record IdempotentOutcome(int httpStatus, String bodyJson) {
}
