package com.example.logaggregator.redis;

import com.example.logaggregator.elasticsearch.LogDocument;
import com.example.logaggregator.elasticsearch.services.LogElasticsearchSearchService;
import com.example.logaggregator.logs.DTOs.LogEntryResponse;
import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import com.example.logaggregator.logs.DTOs.LogSearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.Cacheable;

import java.util.List;

@Service
@Slf4j
public class CachedElasticsearchService {
    private final LogElasticsearchSearchService logElasticsearchSearchService;

    public CachedElasticsearchService(LogElasticsearchSearchService logElasticsearchSearchService) {
        this.logElasticsearchSearchService = logElasticsearchSearchService;
    }

    @Cacheable(
            value = "log-searches",  // Cache name (prefix in Redis)
            key = "#request.serviceId() + ':' + " +
                    "#request.level() + ':' + " +
                    "#request.traceId() + ':' + " +
                    "#request.startTimestamp() + ':' + " +
                    "#request.endTimestamp() + ':' + " +
                    "#request.query() + ':' + " +
                    "#request.page() + ':' + " +
                    "#request.size()",
            unless = "#result.totalElements() == 0"  // Don't cache empty results
    )
    public LogSearchResponse searchWithCache(LogSearchRequest request) {
        log.info("Cache MISS - Executing Elasticsearch query for: {}", request);
        Page<LogDocument> results = logElasticsearchSearchService.search(request);
        List<LogEntryResponse> responses = results.getContent()
                .stream()
                .map(this::documentToResponse)
                .toList();

        LogSearchResponse response = new LogSearchResponse(
                responses,
                results.getTotalElements(),
                results.getTotalPages(),
                results.getNumber(),
                results.getSize()
        );
        log.info("Storing in cache: {} total elements", response.totalElements());
        return response;
    }

    public LogSearchResponse searchWithoutCache(LogSearchRequest request) {
        log.info("Bypassing cache - Direct Elasticsearch query");
        Page<LogDocument> results = logElasticsearchSearchService.search(request);

        List<LogEntryResponse> responses = results.getContent()
                .stream()
                .map(this::documentToResponse)
                .toList();

        return new LogSearchResponse(
                responses,
                results.getTotalElements(),
                results.getTotalPages(),
                results.getNumber(),
                results.getSize()
        );
    }

    // Non-cached version for real-time queries.
    // Use when freshness is critical (e.g., live monitoring dashboards).

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

}
