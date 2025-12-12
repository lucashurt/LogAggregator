package com.example.logaggregator.logs.DTOs;

import com.example.logaggregator.logs.models.LogStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

public record LogEntryRequest(
        @NotNull(message = "timestamp is required")
        Instant timestamp,
        @NotBlank(message = "Service ID is required")
        @Size(max = 100)
        String serviceId,
        @NotNull(message = "Log Level is required")
        LogStatus level,
        @NotBlank(message = "Message is required")
        @Size(max = 10000)
        String message,

        Map<String, Object> metadata,
        String traceId) {
}
