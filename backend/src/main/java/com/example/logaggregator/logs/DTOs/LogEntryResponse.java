package com.example.logaggregator.logs.DTOs;

import com.example.logaggregator.logs.models.LogStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

public record LogEntryResponse(
        @JsonProperty("id") Long id,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("serviceId") String serviceId,
        @JsonProperty("level") LogStatus level,
        @JsonProperty("message") String message,
        @JsonProperty("traceId") String traceId,
        @JsonProperty("metadata") Map<String, Object> metadata,
        @JsonProperty("createdAt") Instant createdAt
) implements Serializable {}
