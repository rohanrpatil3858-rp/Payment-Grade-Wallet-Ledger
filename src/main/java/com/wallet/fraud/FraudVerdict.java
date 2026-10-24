package com.wallet.fraud;

/** The outcome of one fraud rule check. The rule decides; nothing here is LLM-influenced. */
public record FraudVerdict(Action action, String ruleName) {

    public enum Action { HOLD, FLAG_ALLOW }

    public static FraudVerdict hold(String ruleName) {
        return new FraudVerdict(Action.HOLD, ruleName);
    }

    public static FraudVerdict flagAllow(String ruleName) {
        return new FraudVerdict(Action.FLAG_ALLOW, ruleName);
    }
}
