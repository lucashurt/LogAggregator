package com.example.logaggregator.logs.DTOs;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.List;

public record LogSearchResponse(
        @JsonProperty("logs") List<LogEntryResponse> logs,
          @JsonProperty("totalElements") long totalElements,
          @JsonProperty("totalPages") int totalPages,
          @JsonProperty("currentPage") int currentPage,
          @JsonProperty("size") int size) implements Serializable {

            @JsonCreator
            public LogSearchResponse{}


}
