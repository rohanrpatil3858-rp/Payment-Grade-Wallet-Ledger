package com.wallet.llm;

import java.util.function.Function;

/** Test double: returns a fixed, pre-programmed response with no network call and no API key. */
public class FixedResponseLlmClient implements LlmClient {

    private final Function<String, String> responder;

    public FixedResponseLlmClient(String fixedResponse) {
        this.responder = userPrompt -> fixedResponse;
    }

    public FixedResponseLlmClient(Function<String, String> responder) {
        this.responder = responder;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return responder.apply(userPrompt);
    }
}
