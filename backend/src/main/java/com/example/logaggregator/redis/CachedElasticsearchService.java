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

import java.util.Collections;
import java.util.List;

/**
 * FIXED CachedElasticsearchService
 *
 * KEY FIXES:
 * 1. Null-safe handling of CacheManager (prevents NPE in tests)
 * 2. Null-safe handling of Cache object
 * 3. Proper null checks before cache operations
 */
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

        // Build cache key
        String cacheKey = buildCacheKey(request);

        // FIX: Null-safe cache access
        Cache cache = getCache();
        LogSearchResponse cachedResponse = getCachedResponse(cache, cacheKey);

        long retrievalTime = System.currentTimeMillis() - startTime;

        if (cachedResponse != null) {
            // Cache HIT
            log.info("Cache HIT - Retrieved in {}ms", retrievalTime);

            return new LogSearchResponse(
                    cachedResponse.logs(),
                    cachedResponse.totalElements(),
                    cachedResponse.totalPages(),
                    cachedResponse.currentPage(),
                    cachedResponse.size(),
                    retrievalTime,
                    cachedResponse.levelCounts(),
                    cachedResponse.serviceCounts()
            );
        }

        // Cache MISS - execute Elasticsearch query
        log.info("Cache MISS - Executing Elasticsearch query for: {}", request);

        LogElasticsearchSearchService.SearchResultWithMetrics result =
                logElasticsearchSearchService.searchWithMetrics(request);

        // FIX: Null check on result
        if (result == null || result.page() == null) {
            log.warn("Elasticsearch returned null result");
            return createEmptyResponse(System.currentTimeMillis() - startTime);
        }

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
                result.levelCounts() != null ? result.levelCounts() : Collections.emptyMap(),
                result.serviceCounts() != null ? result.serviceCounts() : Collections.emptyMap()
        );

        // Store in cache (only if not empty and cache is available)
        if (response.totalElements() > 0 && cache != null) {
            try {
                cache.put(cacheKey, response);
                log.info("Stored in cache: {} total elements, search took {}ms",
                        response.totalElements(), searchTimeMs);
            } catch (Exception e) {
                log.warn("Failed to cache response: {}", e.getMessage());
            }
        }

        return response;
    }

    public LogSearchResponse searchWithoutCache(LogSearchRequest request) {
        long startTime = System.currentTimeMillis();

        log.info("Bypassing cache - Direct Elasticsearch query");

        LogElasticsearchSearchService.SearchResultWithMetrics result =
                logElasticsearchSearchService.searchWithMetrics(request);

        // FIX: Null check on result
        if (result == null || result.page() == null) {
            log.warn("Elasticsearch returned null result");
            return createEmptyResponse(System.currentTimeMillis() - startTime);
        }

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
                result.levelCounts() != null ? result.levelCounts() : Collections.emptyMap(),
                result.serviceCounts() != null ? result.serviceCounts() : Collections.emptyMap()
        );
    }

    /**
     * FIX: Null-safe cache retrieval
     */
    private Cache getCache() {
        if (cacheManager == null) {
            log.debug("CacheManager is null, caching disabled");
            return null;
        }

        try {
            return cacheManager.getCache("log-searches");
        } catch (Exception e) {
            log.warn("Failed to get cache: {}", e.getMessage());
            return null;
        }
    }

    /**
     * FIX: Null-safe cached response retrieval
     */
    private LogSearchResponse getCachedResponse(Cache cache, String cacheKey) {
        if (cache == null) {
            return null;
        }

        try {
            Cache.ValueWrapper wrapper = cache.get(cacheKey);
            if (wrapper != null) {
                Object value = wrapper.get();
                if (value instanceof LogSearchResponse) {
                    return (LogSearchResponse) value;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve from cache: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Create empty response for error cases
     */
    private LogSearchResponse createEmptyResponse(long searchTimeMs) {
        return new LogSearchResponse(
                Collections.emptyList(),
                0L,
                0,
                0,
                0,
                searchTimeMs,
                Collections.emptyMap(),
                Collections.emptyMap()
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