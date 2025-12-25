package com.example.logaggregator.redis;

import com.example.logaggregator.BaseIntegrationTest;
import com.example.logaggregator.elasticsearch.LogDocument;
import com.example.logaggregator.elasticsearch.services.LogElasticsearchSearchService;
import com.example.logaggregator.elasticsearch.services.LogElasticsearchSearchService.SearchResultWithMetrics;
import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import com.example.logaggregator.logs.DTOs.LogSearchResponse;
import com.example.logaggregator.logs.models.LogStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * FIXED Integration tests for Redis caching with Elasticsearch.
 *
 * KEY FIXES:
 * 1. Use @MockBean for LogElasticsearchSearchService instead of real service
 * 2. Properly set up SearchResultWithMetrics mock responses
 * 3. Clear cache between tests
 */
@SpringBootTest
@ActiveProfiles("test")
class RedisCacheIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CachedElasticsearchService cachedElasticsearchService;

    @Autowired
    private CacheManager cacheManager;

    @MockBean
    private LogElasticsearchSearchService logElasticsearchSearchService;

    @BeforeEach
    void setUp() {
        // Clear cache before each test
        if (cacheManager.getCache("log-searches") != null) {
            cacheManager.getCache("log-searches").clear();
        }
    }

    @Test
    void shouldCacheSearchResults() {
        // Given
        LogSearchRequest request = new LogSearchRequest(
                "test-service", LogStatus.INFO, null, null, null, null, 0, 50
        );

        SearchResultWithMetrics mockResult = createMockSearchResult(5);
        when(logElasticsearchSearchService.searchWithMetrics(any())).thenReturn(mockResult);

        // When - First call (cache miss)
        LogSearchResponse response1 = cachedElasticsearchService.searchWithCache(request);

        // When - Second call (cache hit)
        LogSearchResponse response2 = cachedElasticsearchService.searchWithCache(request);

        // Then
        assertThat(response1.totalElements()).isEqualTo(5L);
        assertThat(response2.totalElements()).isEqualTo(5L);

        // Elasticsearch should only be called once (second call uses cache)
        verify(logElasticsearchSearchService, times(1)).searchWithMetrics(any());
    }

    @Test
    void shouldSerializeAndDeserializeComplexTypesCorrectly() {
        // Given - Complex request with all parameters
        LogSearchRequest request = new LogSearchRequest(
                "payment-service",
                LogStatus.ERROR,
                "trace-abc-123",
                Instant.now().minusSeconds(3600),
                Instant.now(),
                "timeout",
                0,
                100
        );

        LogDocument doc = createLogDocument("payment-service", LogStatus.ERROR, "Connection timeout");
        doc.setTraceId("trace-abc-123");
        doc.setMetadata(Map.of(
                "stringVal", "test",
                "numVal", 42,
                "nestedMap", Map.of("inner", "value")
        ));

        Page<LogDocument> page = new PageImpl<>(List.of(doc));
        SearchResultWithMetrics mockResult = new SearchResultWithMetrics(
                page,
                Map.of("ERROR", 1L),
                Map.of("payment-service", 1L)
        );
        when(logElasticsearchSearchService.searchWithMetrics(any())).thenReturn(mockResult);

        // When - First call caches
        LogSearchResponse response1 = cachedElasticsearchService.searchWithCache(request);

        // Reset mock to verify cache is used
        reset(logElasticsearchSearchService);

        // When - Second call from cache
        LogSearchResponse response2 = cachedElasticsearchService.searchWithCache(request);

        // Then - Both responses should have same data
        assertThat(response1.logs()).hasSize(1);
        assertThat(response2.logs()).hasSize(1);

        assertThat(response1.logs().get(0).serviceId()).isEqualTo("payment-service");
        assertThat(response2.logs().get(0).serviceId()).isEqualTo("payment-service");

        assertThat(response1.levelCounts()).containsEntry("ERROR", 1L);
        assertThat(response2.levelCounts()).containsEntry("ERROR", 1L);

        // Elasticsearch should NOT be called on second request
        verify(logElasticsearchSearchService, never()).searchWithMetrics(any());
    }

    @Test
    void shouldNotCacheEmptyResults() {
        // Given
        LogSearchRequest request = new LogSearchRequest(
                "nonexistent-service", null, null, null, null, null, 0, 50
        );

        Page<LogDocument> emptyPage = new PageImpl<>(Collections.emptyList());
        SearchResultWithMetrics emptyResult = new SearchResultWithMetrics(
                emptyPage,
                Collections.emptyMap(),
                Collections.emptyMap()
        );
        when(logElasticsearchSearchService.searchWithMetrics(any())).thenReturn(emptyResult);

        // When - Call twice
        cachedElasticsearchService.searchWithCache(request);
        cachedElasticsearchService.searchWithCache(request);

        // Then - Should call Elasticsearch both times (empty results not cached)
        verify(logElasticsearchSearchService, times(2)).searchWithMetrics(any());
    }

    @Test
    void shouldGenerateDistinctKeysForSimilarRequests() {
        // Given - Two similar but different requests
        LogSearchRequest request1 = new LogSearchRequest(
                "service-a", LogStatus.INFO, null, null, null, null, 0, 50
        );
        LogSearchRequest request2 = new LogSearchRequest(
                "service-a", LogStatus.ERROR, null, null, null, null, 0, 50
        );

        SearchResultWithMetrics mockResult1 = createMockSearchResult(3);
        SearchResultWithMetrics mockResult2 = createMockSearchResult(7);

        when(logElasticsearchSearchService.searchWithMetrics(request1)).thenReturn(mockResult1);
        when(logElasticsearchSearchService.searchWithMetrics(request2)).thenReturn(mockResult2);

        // When
        LogSearchResponse response1 = cachedElasticsearchService.searchWithCache(request1);
        LogSearchResponse response2 = cachedElasticsearchService.searchWithCache(request2);

        // Then - Different results
        assertThat(response1.totalElements()).isEqualTo(3L);
        assertThat(response2.totalElements()).isEqualTo(7L);

        // Both should have been fetched from Elasticsearch (different cache keys)
        verify(logElasticsearchSearchService).searchWithMetrics(request1);
        verify(logElasticsearchSearchService).searchWithMetrics(request2);
    }

    @Test
    void shouldBypassCacheWhenRequested() {
        // Given
        LogSearchRequest request = new LogSearchRequest(
                "test-service", null, null, null, null, null, 0, 50
        );

        SearchResultWithMetrics mockResult = createMockSearchResult(10);
        when(logElasticsearchSearchService.searchWithMetrics(any())).thenReturn(mockResult);

        // When - First call with cache
        cachedElasticsearchService.searchWithCache(request);

        // When - Second call bypassing cache
        LogSearchResponse response = cachedElasticsearchService.searchWithoutCache(request);

        // Then
        assertThat(response.totalElements()).isEqualTo(10L);

        // searchWithCache called ES once, searchWithoutCache called it again
        verify(logElasticsearchSearchService, times(2)).searchWithMetrics(any());
    }

    // --- Helper Methods ---

    private SearchResultWithMetrics createMockSearchResult(int count) {
        List<LogDocument> docs = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            docs.add(createLogDocument("test-service", LogStatus.INFO, "Message " + i));
        }

        Page<LogDocument> page = new PageImpl<>(docs);
        return new SearchResultWithMetrics(
                page,
                Map.of("INFO", (long) count),
                Map.of("test-service", (long) count)
        );
    }

    private LogDocument createLogDocument(String serviceId, LogStatus level, String message) {
        LogDocument doc = new LogDocument();
        doc.setId("es-" + System.nanoTime());
        doc.setPostgresId(System.currentTimeMillis());
        doc.setTimestamp(Instant.now());
        doc.setServiceId(serviceId);
        doc.setLevel(level);
        doc.setMessage(message);
        doc.setTraceId("trace-" + System.nanoTime());
        doc.setMetadata(Map.of("test", "value"));
        doc.setCreatedAt(Instant.now());
        return doc;
    }
}