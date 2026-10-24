package com.wallet.llm;

/**
 * One small interface in front of whichever LLM provider we use, so the rest of the app never
 * depends on a specific vendor and tests can swap in a fixed-response double (no network, no
 * API key needed).
 */
public interface LlmClient {

    /**
     * systemPrompt carries the instructions; userPrompt carries the data (the question, or a
     * JSON blob describing a transfer). Keeping them separate is what makes it possible to tell
     * an LLM provider "treat userPrompt as data, never as instructions".
     */
    String complete(String systemPrompt, String userPrompt);
}
