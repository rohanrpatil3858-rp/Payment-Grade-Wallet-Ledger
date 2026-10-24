package com.wallet.transfer;

/** Body of POST /transfers (wallet to wallet). userId is the sender, explicit since no auth layer exists yet. */
public record PeerTransferRequest(long userId, long senderAccountId, long recipientAccountId, long amountPaise, String note) {
}
