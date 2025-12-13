package com.example.logaggregator.logs;

import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import com.example.logaggregator.logs.DTOs.LogEntryResponse;
import com.example.logaggregator.logs.models.LogEntry;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/logs")
public class LogController {
    private final LogService logService;
    public LogController(LogService logService) {
        this.logService = logService;
    }

    @PostMapping
    public ResponseEntity<LogEntryResponse> ingestLog(@Valid @RequestBody LogEntryRequest request) {
        LogEntry response = logService.ingest(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(response));
    }

    @PostMapping("/batch")
    public ResponseEntity<List<LogEntryResponse>> ingestBatch(@Valid @RequestBody List<LogEntryRequest> request) {
        List<LogEntry> responses = logService.ingestBatch(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responses.stream()
                        .map(this::toResponse)
                        .collect(Collectors.toList()));
    }


    private LogEntryResponse toResponse(LogEntry logEntry){
        return new LogEntryResponse(
                logEntry.getId(),
                logEntry.getTimestamp(),
                logEntry.getServiceId(),
                logEntry.getLevel(),
                logEntry.getMessage(),
                logEntry.getTraceId(),
                logEntry.getMetadata(),
                logEntry.getCreatedAt()
        );
    }
}
