package com.example.logaggregator.logs.DTOs;

import com.example.logaggregator.logs.models.LogStatus;

import java.time.Instant;

public record LogSearchRequest(String serviceId, LogStatus level, String traceId, Instant startTimestamp, Instant endTimestamp, String query, Integer page, Integer size) {
    public LogSearchRequest{
        page = (page != null && page >= 0) ? page : 0;
        size = (size != null && size > 0 && size <=1000) ? size : 50;
    }
}
