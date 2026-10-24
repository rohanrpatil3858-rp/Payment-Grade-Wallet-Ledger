package com.wallet.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * One-off smoke test against the real OpenRouter provider. Automatically skipped (not run as
 * part of the normal suite / CI) unless OPENROUTER_API_KEY is actually set, so this never needs
 * network access or a key to pass in the default test run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class OpenRouterSmokeTest {

    @Autowired
    private LlmClient llmClient;

    @Test
    void realProviderRespondsToASimplePrompt() {
        String response = llmClient.complete(
                "Respond with ONLY the single word: pong",
                "ping");

        System.out.println("OpenRouter smoke test response: " + response);
        assertNotNull(response);
        assertFalse(response.isBlank());
    }
}
