package com.wallet.statement;

import java.time.LocalDate;

/** The LLM's JSON extracted from a plain-English statement question, validated before use. */
record ParsedQuestion(int type, String counterparty, LocalDate from, LocalDate to) {
}
