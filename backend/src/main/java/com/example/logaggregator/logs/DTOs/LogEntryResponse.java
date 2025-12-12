package com.example.logaggregator.logs.DTOs;

import com.example.logaggregator.logs.models.LogStatus;

import java.time.Instant;
import java.util.Map;

public record LogEntryResponse(Long id, Instant timestamp, String serviceId, LogStatus level, String message, Map<String, Object> metadata, String traceId, Instant createdAt) {
}
