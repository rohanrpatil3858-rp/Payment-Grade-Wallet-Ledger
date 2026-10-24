package com.wallet.statement;

import java.util.List;

/** Answer to a statement question - either a number backed by real ledger rows, or "unsupported". */
public record StatementAnswer(boolean supported, String message, Long value, List<Long> transferIds) {

    public static StatementAnswer numeric(long value, List<Long> transferIds) {
        return new StatementAnswer(true, null, value, transferIds);
    }

    public static StatementAnswer unsupported() {
        return new StatementAnswer(false, "I can only answer these four kinds of question.", null, List.of());
    }
}
