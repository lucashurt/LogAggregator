package com.example.logaggregator.logs;

import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import com.example.logaggregator.logs.DTOs.LogEntryResponse;
import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import com.example.logaggregator.logs.DTOs.LogSearchResponse;
import com.example.logaggregator.logs.models.LogEntry;
import com.example.logaggregator.logs.models.LogStatus;
import com.example.logaggregator.logs.services.LogIngestService;
import com.example.logaggregator.logs.services.LogSearchService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/logs")
public class LogController {
    private final LogIngestService logIngestService;
    private final LogSearchService logSearchService;

    public LogController(LogIngestService logIngestService, LogSearchService logSearchService) {
        this.logIngestService = logIngestService;
        this.logSearchService = logSearchService;
    }

    @PostMapping
    public ResponseEntity<LogEntryResponse> ingestLog(@Valid @RequestBody LogEntryRequest request) {
        LogEntry response = logIngestService.ingest(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(response));
    }

    @PostMapping("/batch")
    public ResponseEntity<List<LogEntryResponse>> ingestBatch(@Valid @RequestBody List<LogEntryRequest> request) {
        List<LogEntry> responses = logIngestService.ingestBatch(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responses.stream()
                        .map(this::toResponse)
                        .collect(Collectors.toList()));
    }

    @GetMapping("/search")
    public ResponseEntity<LogSearchResponse> searchLog(
            @RequestParam(required = false) String serviceId,
            @RequestParam(required = false) LogStatus level,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) Instant startTime,
            @RequestParam(required = false) Instant endTime,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "50") Integer size)
    {
        LogSearchRequest request = new LogSearchRequest(serviceId,level,traceId,startTime,endTime,query,page,size);
        Page<LogEntry> logResponses = logSearchService.search(request);
        List<LogEntryResponse> responses = logResponses.getContent()
                .stream().map(this::toResponse)
                .toList();

        LogSearchResponse response = new LogSearchResponse(
                responses,
                logResponses.getTotalElements(),
                logResponses.getTotalPages(),
                logResponses.getNumber(),
                logResponses.getSize());

        return ResponseEntity.ok(response);
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
