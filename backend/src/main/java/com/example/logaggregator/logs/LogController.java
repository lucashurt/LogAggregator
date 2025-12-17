package com.example.logaggregator.logs;

import com.example.logaggregator.kafka.ConsumersAndProducers.LogProducer;
import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import com.example.logaggregator.logs.DTOs.LogEntryResponse;
import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import com.example.logaggregator.logs.DTOs.LogSearchResponse;
import com.example.logaggregator.logs.models.LogEntry;
import com.example.logaggregator.logs.models.LogStatus;
import com.example.logaggregator.logs.services.LogPostgresService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/logs")
public class LogController {
    LogProducer logProducer;
    private final LogPostgresService logPostgresService;

    public LogController(LogProducer logProducer, LogPostgresService logPostgresService) {
        this.logProducer = logProducer;
        this.logPostgresService = logPostgresService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> ingestLog(@Valid @RequestBody LogEntryRequest request) {
        logProducer.sendLog(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("Status", "Log accepted for processing"));
    }

    @PostMapping("/batch")
    public ResponseEntity<Map<String, String>> ingestBatch(@Valid @RequestBody List<LogEntryRequest> request) {
        logProducer.sendLogBatch(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", request.size() + " logs accepted for processing"));
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
        Page<LogEntry> logResponses = logPostgresService.search(request);
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
