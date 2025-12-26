package com.example.logaggregator.redis;

import com.example.logaggregator.elasticsearch.LogDocument;
import com.example.logaggregator.elasticsearch.services.LogElasticsearchSearchService;
import com.example.logaggregator.elasticsearch.services.LogElasticsearchSearchService.SearchResultWithMetrics;
import com.example.logaggregator.logs.DTOs.LogEntryResponse;
import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import com.example.logaggregator.logs.DTOs.LogSearchResponse;
import com.example.logaggregator.logs.models.LogStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * FIXED Unit tests for CachedElasticsearchService.
 *
 * KEY FIXES:
 * 1. Properly mock CacheManager (was null before)
 * 2. Properly mock SearchResultWithMetrics (was returning null)
 * 3. Handle nullable cache scenarios
 */
@ExtendWith(MockitoExtension.class)
class CachedElasticsearchServiceTest {

    @Mock
    private LogElasticsearchSearchService logElasticsearchSearchService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache cache;

    @Mock
    private Cache.ValueWrapper valueWrapper;

    private CachedElasticsearchService cachedElasticsearchService;

    @BeforeEach
    void setUp() {
        cachedElasticsearchService = new CachedElasticsearchService(
                logElasticsearchSearchService,
                cacheManager
        );
    }

    @Test
    void shouldReturnCachedResponseOnCacheHit() {
        // Given
        LogSearchRequest request = new LogSearchRequest(
                "test-service", LogStatus.INFO, null, null, null, null, 0, 50
        );

        LogSearchResponse cachedResponse = new LogSearchResponse(
                List.of(createSampleLogResponse()),
                1L, 1, 0, 50, 5L,
                Map.of("INFO", 1L),
                Map.of("test-service", 1L)
        );

        when(cacheManager.getCache("log-searches")).thenReturn(cache);
        when(cache.get(anyString())).thenReturn(valueWrapper);
        when(valueWrapper.get()).thenReturn(cachedResponse);

        // When
        LogSearchResponse response = cachedElasticsearchService.searchWithCache(request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.totalElements()).isEqualTo(1L);
        verify(logElasticsearchSearchService, never()).searchWithMetrics(any());
    }

    @Test
    void shouldQueryElasticsearchOnCacheMiss() {
        // Given
        LogSearchRequest request = new LogSearchRequest(
                "test-service", LogStatus.INFO, null, null, null, null, 0, 50
        );

        Page<LogDocument> page = new PageImpl<>(List.of(createSampleLogDocument()));
        SearchResultWithMetrics searchResult = new SearchResultWithMetrics(
                page,
                Map.of("INFO", 1L),
                Map.of("test-service", 1L)
        );

        when(cacheManager.getCache("log-searches")).thenReturn(cache);
        when(cache.get(anyString())).thenReturn(null); // Cache miss
        when(logElasticsearchSearchService.searchWithMetrics(any())).thenReturn(searchResult);

        // When
        LogSearchResponse response = cachedElasticsearchService.searchWithCache(request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.totalElements()).isEqualTo(1L);
        verify(logElasticsearchSearchService).searchWithMetrics(request);
        verify(cache).put(anyString(), any(LogSearchResponse.class));
    }

    @Test
    void shouldMapAllFieldsCorrectly() {
        // Given
        LogSearchRequest request = new LogSearchRequest(
                "payment-service", LogStatus.ERROR, "trace-123",
                Instant.now().minusSeconds(3600), Instant.now(),
                "timeout", 0, 100
        );

        LogDocument doc = createSampleLogDocument();
        doc.setServiceId("payment-service");
        doc.setLevel(LogStatus.ERROR);
        doc.setTraceId("trace-123");
        doc.setMessage("Connection timeout occurred");

        Page<LogDocument> page = new PageImpl<>(List.of(doc));
        SearchResultWithMetrics searchResult = new SearchResultWithMetrics(
                page,
                Map.of("ERROR", 1L),
                Map.of("payment-service", 1L)
        );

        when(cacheManager.getCache("log-searches")).thenReturn(cache);
        when(cache.get(anyString())).thenReturn(null);
        when(logElasticsearchSearchService.searchWithMetrics(any())).thenReturn(searchResult);

        // When
        LogSearchResponse response = cachedElasticsearchService.searchWithCache(request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.logs()).hasSize(1);

        LogEntryResponse logResponse = response.logs().get(0);
        assertThat(logResponse.serviceId()).isEqualTo("payment-service");
        assertThat(logResponse.level()).isEqualTo(LogStatus.ERROR);
        assertThat(logResponse.traceId()).isEqualTo("trace-123");

        assertThat(response.levelCounts()).containsEntry("ERROR", 1L);
        assertThat(response.serviceCounts()).containsEntry("payment-service", 1L);
    }

    @Test
    void shouldPropagateExceptions() {
        // Given
        LogSearchRequest request = new LogSearchRequest(
                null, null, null, null, null, null, 0, 50
        );

        when(cacheManager.getCache("log-searches")).thenReturn(cache);
        when(cache.get(anyString())).thenReturn(null); // Cache miss
        when(logElasticsearchSearchService.searchWithMetrics(any()))
                .thenThrow(new RuntimeException("Elasticsearch is down"));

        // When/Then
        assertThatThrownBy(() -> cachedElasticsearchService.searchWithCache(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Elasticsearch is down");
    }

    @Test
    void shouldSearchWithoutCache() {
        // Given
        LogSearchRequest request = new LogSearchRequest(
                "test-service", null, null, null, null, null, 0, 50
        );

        Page<LogDocument> page = new PageImpl<>(List.of(createSampleLogDocument()));
        SearchResultWithMetrics searchResult = new SearchResultWithMetrics(
                page,
                Map.of("INFO", 1L),
                Map.of("test-service", 1L)
        );

        when(logElasticsearchSearchService.searchWithMetrics(any())).thenReturn(searchResult);

        // When
        LogSearchResponse response = cachedElasticsearchService.searchWithoutCache(request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.totalElements()).isEqualTo(1L);
        verify(logElasticsearchSearchService).searchWithMetrics(request);
        verify(cacheManager, never()).getCache(anyString()); // Cache not accessed
    }

    @Test
    void shouldHandleNullCache() {
        // Given - CacheManager returns null cache
        LogSearchRequest request = new LogSearchRequest(
                "test-service", null, null, null, null, null, 0, 50
        );

        Page<LogDocument> page = new PageImpl<>(List.of(createSampleLogDocument()));
        SearchResultWithMetrics searchResult = new SearchResultWithMetrics(
                page,
                Map.of("INFO", 1L),
                Map.of("test-service", 1L)
        );

        when(cacheManager.getCache("log-searches")).thenReturn(null); // Null cache
        when(logElasticsearchSearchService.searchWithMetrics(any())).thenReturn(searchResult);

        // When
        LogSearchResponse response = cachedElasticsearchService.searchWithCache(request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.totalElements()).isEqualTo(1L);
    }

    @Test
    void shouldNotCacheEmptyResults() {
        // Given
        LogSearchRequest request = new LogSearchRequest(
                "nonexistent-service", null, null, null, null, null, 0, 50
        );

        Page<LogDocument> emptyPage = new PageImpl<>(Collections.emptyList());
        SearchResultWithMetrics searchResult = new SearchResultWithMetrics(
                emptyPage,
                Collections.emptyMap(),
                Collections.emptyMap()
        );

        when(cacheManager.getCache("log-searches")).thenReturn(cache);
        when(cache.get(anyString())).thenReturn(null);
        when(logElasticsearchSearchService.searchWithMetrics(any())).thenReturn(searchResult);

        // When
        LogSearchResponse response = cachedElasticsearchService.searchWithCache(request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.totalElements()).isEqualTo(0L);
        verify(cache, never()).put(anyString(), any()); // Should NOT cache empty results
    }

    // --- Helper Methods ---

    private LogDocument createSampleLogDocument() {
        LogDocument doc = new LogDocument();
        doc.setId("es-123");
        doc.setPostgresId(1L);
        doc.setTimestamp(Instant.now());
        doc.setServiceId("test-service");
        doc.setLevel(LogStatus.INFO);
        doc.setMessage("Test log message");
        doc.setTraceId("trace-abc");
        doc.setMetadata(Map.of("key", "value"));
        doc.setCreatedAt(Instant.now());
        return doc;
    }

    private LogEntryResponse createSampleLogResponse() {
        return new LogEntryResponse(
                1L,
                Instant.now(),
                "test-service",
                LogStatus.INFO,
                "Test message",
                "trace-123",
                Map.of("key", "value"),
                Instant.now()
        );
    }
}