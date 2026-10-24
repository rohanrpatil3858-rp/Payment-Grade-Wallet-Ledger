package com.wallet.transfer;

/**
 * Outcome of a transfer attempt. Mirrors the API contract's single error shape
 * ({"code": ..., "message": ...}) so controllers can reuse it directly later.
 */
public record TransferResult(boolean success, Long transferId, String errorCode) {

    public static TransferResult success(long transferId) {
        return new TransferResult(true, transferId, null);
    }

    public static TransferResult insufficientFunds() {
        return new TransferResult(false, null, "INSUFFICIENT_FUNDS");
    }
}
