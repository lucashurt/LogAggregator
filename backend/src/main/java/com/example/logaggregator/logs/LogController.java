package com.example.logaggregator.logs;

import com.example.logaggregator.elasticsearch.LogDocument;
import com.example.logaggregator.elasticsearch.services.LogElasticsearchSearchService;
import com.example.logaggregator.kafka.ConsumersAndProducers.LogProducer;
import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import com.example.logaggregator.logs.DTOs.LogEntryResponse;
import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import com.example.logaggregator.logs.DTOs.LogSearchResponse;
import com.example.logaggregator.logs.models.LogStatus;
import com.example.logaggregator.logs.services.LogPostgresSearchService;
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

    private final LogProducer logProducer;
    private final LogElasticsearchSearchService elasticsearchSearchService;
    private final LogPostgresSearchService postgresSearchService; // Keep as fallback

    public LogController(
            LogProducer logProducer,
            LogElasticsearchSearchService elasticsearchSearchService,
            LogPostgresSearchService postgresSearchService) {
        this.logProducer = logProducer;
        this.elasticsearchSearchService = elasticsearchSearchService;
        this.postgresSearchService = postgresSearchService;
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

    /**
     * Search logs - NOW USING ELASTICSEARCH!
     */
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
        LogSearchRequest request = new LogSearchRequest(
                serviceId, level, traceId, startTime, endTime, query, page, size
        );

        try {
            // Use Elasticsearch for search
            Page<LogDocument> elasticsearchResults = elasticsearchSearchService.search(request);

            List<LogEntryResponse> responses = elasticsearchResults.getContent()
                    .stream()
                    .map(this::documentToResponse)
                    .toList();

            LogSearchResponse response = new LogSearchResponse(
                    responses,
                    elasticsearchResults.getTotalElements(),
                    elasticsearchResults.getTotalPages(),
                    elasticsearchResults.getNumber(),
                    elasticsearchResults.getSize()
            );

            log.info("Search completed via Elasticsearch: found {} results", responses.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            // Fallback to PostgreSQL if Elasticsearch fails
            log.warn("Elasticsearch search failed, falling back to PostgreSQL: {}", e.getMessage());

            Page<com.example.logaggregator.logs.models.LogEntry> postgresResults =
                    postgresSearchService.search(request);

            List<LogEntryResponse> responses = postgresResults.getContent()
                    .stream()
                    .map(this::toResponse)
                    .toList();

            LogSearchResponse response = new LogSearchResponse(
                    responses,
                    postgresResults.getTotalElements(),
                    postgresResults.getTotalPages(),
                    postgresResults.getNumber(),
                    postgresResults.getSize()
            );

            return ResponseEntity.ok(response);
        }
    }

    private LogEntryResponse documentToResponse(LogDocument document) {
        return new LogEntryResponse(
                document.getPostgresId(),
                document.getTimestamp(),
                document.getServiceId(),
                document.getLevel(),
                document.getMessage(),
                document.getTraceId(),
                document.getMetadata(),
                document.getCreatedAt()
        );
    }

    private LogEntryResponse toResponse(com.example.logaggregator.logs.models.LogEntry logEntry) {
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