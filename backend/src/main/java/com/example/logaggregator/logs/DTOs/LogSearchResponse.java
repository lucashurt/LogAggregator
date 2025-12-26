package com.example.logaggregator.logs.DTOs;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public record LogSearchResponse(
        @JsonProperty("logs") List<LogEntryResponse> logs,
        @JsonProperty("totalElements") long totalElements,
        @JsonProperty("totalPages") int totalPages,
        @JsonProperty("currentPage") int currentPage,
        @JsonProperty("size") int size,
        @JsonProperty("searchTimeMs") long searchTimeMs,
        @JsonProperty("levelCounts") Map<String, Long> levelCounts,
        @JsonProperty("serviceCounts") Map<String, Long> serviceCounts
) implements Serializable {
}
