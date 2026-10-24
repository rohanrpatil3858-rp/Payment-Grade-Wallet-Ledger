package com.wallet.llm;

/** Thrown when the LLM provider cannot be reached or returns an unusable response. */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
