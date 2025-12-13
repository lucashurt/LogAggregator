package com.example.logaggregator.logs.DTOs;

import com.example.logaggregator.logs.models.LogStatus;

import java.time.Instant;

public record LogSearchRequest(String serviceId, LogStatus level, String traceId, Instant startTimestamp, Instant endTimestam, String query, Integer page, Integer size) {
}
