package com.wallet.transfer;

/** Body of POST /transfers/topup. */
public record TopUpRequest(long userId, long walletAccountId, long amountPaise) {
}
