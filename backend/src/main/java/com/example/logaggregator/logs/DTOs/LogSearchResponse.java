package com.example.logaggregator.logs.DTOs;

import java.util.List;

public record LogSearchResponse(List<LogEntryResponse> logs, long totalElements, int totalPages, int currentPage, int size) {
}
