package com.example.logaggregator.redis;

import com.example.logaggregator.elasticsearch.LogDocument;
import com.example.logaggregator.elasticsearch.services.LogElasticsearchSearchService;
import com.example.logaggregator.logs.DTOs.LogEntryResponse;
import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import com.example.logaggregator.logs.DTOs.LogSearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class CachedElasticsearchService {
    private final LogElasticsearchSearchService logElasticsearchSearchService;
    private final CacheManager cacheManager;

    public CachedElasticsearchService(
            LogElasticsearchSearchService logElasticsearchSearchService,
            CacheManager cacheManager) {
        this.logElasticsearchSearchService = logElasticsearchSearchService;
        this.cacheManager = cacheManager;
    }

    public LogSearchResponse searchWithCache(LogSearchRequest request) {
        long startTime = System.currentTimeMillis();

        // Build cache key (same format as before)
        String cacheKey = buildCacheKey(request);

        Cache cache = cacheManager.getCache("log-searches");
        LogSearchResponse cachedResponse = null;

        if (cache != null) {
            Cache.ValueWrapper wrapper = cache.get(cacheKey);
            if (wrapper != null) {
                cachedResponse = (LogSearchResponse) wrapper.get();
            }
        }

        long retrievalTime = System.currentTimeMillis() - startTime;

        if (cachedResponse != null) {
            // Cache HIT - return with updated timing showing cache speed
            log.info("Cache HIT - Retrieved in {}ms", retrievalTime);

            return new LogSearchResponse(
                    cachedResponse.logs(),
                    cachedResponse.totalElements(),
                    cachedResponse.totalPages(),
                    cachedResponse.currentPage(),
                    cachedResponse.size(),
                    retrievalTime, // Show actual cache retrieval time (fast!)
                    cachedResponse.levelCounts(),
                    cachedResponse.serviceCounts()
            );
        }

        // Cache MISS - execute Elasticsearch query
        log.info("Cache MISS - Executing Elasticsearch query for: {}", request);

        LogElasticsearchSearchService.SearchResultWithMetrics result =
                logElasticsearchSearchService.searchWithMetrics(request);

        Page<LogDocument> page = result.page();

        List<LogEntryResponse> responses = page.getContent()
                .stream()
                .map(this::documentToResponse)
                .toList();

        long searchTimeMs = System.currentTimeMillis() - startTime;

        LogSearchResponse response = new LogSearchResponse(
                responses,
                page.getTotalElements(),
                page.getTotalPages(),
                page.getNumber(),
                page.getSize(),
                searchTimeMs,
                result.levelCounts(),
                result.serviceCounts()
        );

        // Store in cache (only if not empty)
        if (response.totalElements() > 0 && cache != null) {
            cache.put(cacheKey, response);
            log.info("Stored in cache: {} total elements, search took {}ms",
                    response.totalElements(), searchTimeMs);
        }

        return response;
    }

    public LogSearchResponse searchWithoutCache(LogSearchRequest request) {
        long startTime = System.currentTimeMillis();

        log.info("Bypassing cache - Direct Elasticsearch query");

        LogElasticsearchSearchService.SearchResultWithMetrics result =
                logElasticsearchSearchService.searchWithMetrics(request);

        Page<LogDocument> page = result.page();

        List<LogEntryResponse> responses = page.getContent()
                .stream()
                .map(this::documentToResponse)
                .toList();

        long searchTimeMs = System.currentTimeMillis() - startTime;

        return new LogSearchResponse(
                responses,
                page.getTotalElements(),
                page.getTotalPages(),
                page.getNumber(),
                page.getSize(),
                searchTimeMs,
                result.levelCounts(),
                result.serviceCounts()
        );
    }

    /**
     * Build cache key matching the @Cacheable key format
     */
    private String buildCacheKey(LogSearchRequest request) {
        return (request.serviceId() == null ? "" : request.serviceId()) + ":" +
                (request.level() == null ? "" : request.level()) + ":" +
                (request.traceId() == null ? "" : request.traceId()) + ":" +
                (request.startTimestamp() == null ? "" : request.startTimestamp()) + ":" +
                (request.endTimestamp() == null ? "" : request.endTimestamp()) + ":" +
                (request.query() == null ? "" : request.query()) + ":" +
                request.page() + ":" +
                request.size();
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
}