package com.wallet.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * End-to-end test of the API contract: create users and wallets, top up, send money,
 * read balance and statement, fetch a transfer, and reverse a completed transfer - all through
 * the REST endpoints, exactly as a client would.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiContractEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fullMoneyPathThroughTheApi() throws Exception {
        long ashaId = createUser("Asha");
        long rahulId = createUser("Rahul");
        long ashaWallet = openWallet(ashaId);
        long rahulWallet = openWallet(rahulId);

        // Top up Asha's wallet with Rs 500.
        postJson("/transfers/topup", Map.of("userId", ashaId, "walletAccountId", ashaWallet, "amountPaise", 50000), 201);
        assertEquals(50000, balanceOf(ashaWallet));

        // Asha sends Rahul Rs 200.
        MvcResult send = postJson("/transfers",
                Map.of("userId", ashaId, "senderAccountId", ashaWallet, "recipientAccountId", rahulWallet,
                        "amountPaise", 20000, "note", "lunch"),
                201);
        long transferId = objectMapper.readTree(send.getResponse().getContentAsString()).get("transferId").asLong();

        assertEquals(30000, balanceOf(ashaWallet));
        assertEquals(20000, balanceOf(rahulWallet));

        // Statement shows the entries newest first.
        MvcResult statement = mockMvc.perform(get("/wallets/{id}/statement", ashaWallet)).andReturn();
        JsonNode entries = objectMapper.readTree(statement.getResponse().getContentAsString()).get("entries");
        assertEquals(2, entries.size(), "top-up credit + peer-transfer debit");

        // GET the peer transfer and confirm its details.
        MvcResult transferDetail = mockMvc.perform(get("/transfers/{id}", transferId)).andReturn();
        JsonNode transfer = objectMapper.readTree(transferDetail.getResponse().getContentAsString()).get("transfer");
        assertEquals("peer_transfer", transfer.get("type").asText());

        // Reverse the Rs 200 transfer - money flows back.
        postJson("/transfers/" + transferId + "/reverse", Map.of("userId", ashaId), 201);
        assertEquals(50000, balanceOf(ashaWallet));
        assertEquals(0, balanceOf(rahulWallet));
    }

    @Test
    void missingIdempotencyKeyOnTopUpIsRejected() throws Exception {
        long userId = createUser("NoKey");
        long wallet = openWallet(userId);
        MvcResult result = mockMvc.perform(post("/transfers/topup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("userId", userId, "walletAccountId", wallet, "amountPaise", 1000))))
                .andReturn();
        assertEquals(400, result.getResponse().getStatus());
    }

    private long createUser(String name) throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString();
        MvcResult result = mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "email", "api-" + uniqueSuffix + "@example.com",
                                "phone", "9" + (System.nanoTime() % 1_000_000_000L)))))
                .andReturn();
        assertEquals(201, result.getResponse().getStatus());
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("userId").asLong();
    }

    private long openWallet(long userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/users/{id}/wallets", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertEquals(201, result.getResponse().getStatus());
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("walletId").asLong();
    }

    private long balanceOf(long walletId) throws Exception {
        MvcResult result = mockMvc.perform(get("/wallets/{id}/balance", walletId)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("balancePaise").asLong();
    }

    private MvcResult postJson(String url, Map<String, Object> body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post(url)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus(),
                "POST " + url + " body: " + result.getResponse().getContentAsString());
        return result;
    }
}
