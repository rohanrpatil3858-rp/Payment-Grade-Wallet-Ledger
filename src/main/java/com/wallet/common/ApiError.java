package com.wallet.common;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The single error JSON shape used across every endpoint, per the API contract. */
public record ApiError(String code, String message, @JsonProperty("request_id") String requestId) {
}
