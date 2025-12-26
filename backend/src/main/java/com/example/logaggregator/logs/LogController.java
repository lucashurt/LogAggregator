package com.example.logaggregator.logs;

import com.example.logaggregator.redis.CachedElasticsearchService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/logs")
public class LogController {

    private final LogProducer logProducer;
    private final CachedElasticsearchService cachedElasticsearchService;
    private final LogPostgresSearchService postgresSearchService;

    public LogController(
            LogProducer logProducer,
            CachedElasticsearchService cachedElasticsearchService,
            LogPostgresSearchService postgresSearchService) {
        this.logProducer = logProducer;
        this.cachedElasticsearchService = cachedElasticsearchService;
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
     * Search logs - Using Elasticsearch with fallback to PostgreSQL
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

        log.info("🔍 Search request: serviceId={}, level={}, page={}, size={}",
                serviceId, level, page, size);

        try {
            // Use Elasticsearch for search
            LogSearchResponse response = cachedElasticsearchService.searchWithCache(request);

            // Log aggregation info to verify they're working
            log.info("✅ Elasticsearch search completed: {} results, {} total, {}ms",
                    response.logs().size(),
                    response.totalElements(),
                    response.searchTimeMs());
            log.info("   Level counts: {}", response.levelCounts());
            log.info("   Service counts: {} services", response.serviceCounts().size());

            // Verify we got aggregation data (not just page-level counts)
            long aggregationTotal = response.levelCounts().values().stream()
                    .mapToLong(Long::longValue).sum();
            if (aggregationTotal > 0 && aggregationTotal != response.logs().size()) {
                log.info("   ✅ Aggregations are TOTAL counts (sum={} vs page={})",
                        aggregationTotal, response.logs().size());
            } else if (aggregationTotal == response.logs().size() && response.totalElements() > response.logs().size()) {
                log.warn("   ⚠️ Aggregations might be PAGE-LEVEL counts! " +
                                "sum={}, pageSize={}, totalElements={}",
                        aggregationTotal, response.logs().size(), response.totalElements());
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            // Fallback to PostgreSQL if Elasticsearch fails
            log.warn("⚠️ Elasticsearch search failed, falling back to PostgreSQL: {}",
                    e.getMessage());
            log.warn("   Exception type: {}", e.getClass().getSimpleName());

            // Log full stack trace at debug level
            log.debug("Full exception:", e);

            long startTimeMs = System.currentTimeMillis();

            Page<com.example.logaggregator.logs.models.LogEntry> postgresResults =
                    postgresSearchService.search(request);

            List<LogEntryResponse> responses = postgresResults.getContent()
                    .stream()
                    .map(this::toResponse)
                    .toList();

            // WARNING: These are PAGE-LEVEL counts only (PostgreSQL fallback limitation)
            Map<String, Long> levelCounts = calculateLevelCounts(postgresResults.getContent());
            Map<String, Long> serviceCounts = calculateServiceCounts(postgresResults.getContent());

            long searchTimeMs = System.currentTimeMillis() - startTimeMs;

            log.warn("⚠️ PostgreSQL fallback completed: {} results, {} total",
                    responses.size(), postgresResults.getTotalElements());
            log.warn("   ⚠️ Level/Service counts are PAGE-LEVEL only (not total): {}",
                    levelCounts);

            LogSearchResponse response = new LogSearchResponse(
                    responses,
                    postgresResults.getTotalElements(),
                    postgresResults.getTotalPages(),
                    postgresResults.getNumber(),
                    postgresResults.getSize(),
                    searchTimeMs,
                    levelCounts,
                    serviceCounts
            );

            return ResponseEntity.ok(response);
        }
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

    private Map<String, Long> calculateLevelCounts(List<com.example.logaggregator.logs.models.LogEntry> logs) {
        return logs.stream()
                .collect(Collectors.groupingBy(
                        log -> log.getLevel().toString(),
                        Collectors.counting()
                ));
    }

    private Map<String, Long> calculateServiceCounts(List<com.example.logaggregator.logs.models.LogEntry> logs) {
        return logs.stream()
                .collect(Collectors.groupingBy(
                        com.example.logaggregator.logs.models.LogEntry::getServiceId,
                        Collectors.counting()
                ));
    }
}