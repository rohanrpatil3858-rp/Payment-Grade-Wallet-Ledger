package com.wallet.transfer;

/** Outcome of a peer-to-peer send-money attempt. */
public record PeerTransferResult(Outcome outcome, Long transferId) {

    public enum Outcome { COMPLETED, HELD, INSUFFICIENT_FUNDS }

    public static PeerTransferResult completed(long transferId) {
        return new PeerTransferResult(Outcome.COMPLETED, transferId);
    }

    public static PeerTransferResult held(long transferId) {
        return new PeerTransferResult(Outcome.HELD, transferId);
    }

    public static PeerTransferResult insufficientFunds() {
        return new PeerTransferResult(Outcome.INSUFFICIENT_FUNDS, null);
    }
}
