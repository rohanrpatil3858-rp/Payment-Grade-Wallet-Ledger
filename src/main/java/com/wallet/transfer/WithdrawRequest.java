package com.wallet.transfer;

/** Body of POST /transfers/withdraw. userId is explicit here since no auth layer exists yet. */
public record WithdrawRequest(long userId, long walletAccountId, long amountPaise) {
}
