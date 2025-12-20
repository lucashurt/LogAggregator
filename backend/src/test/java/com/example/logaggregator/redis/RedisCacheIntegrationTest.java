package com.example.logaggregator.redis;

import com.example.logaggregator.BaseIntegrationTest;
import com.example.logaggregator.elasticsearch.LogDocument;
import com.example.logaggregator.elasticsearch.services.LogElasticsearchSearchService;
import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import com.example.logaggregator.logs.DTOs.LogSearchResponse;
import com.example.logaggregator.logs.models.LogStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
class RedisCacheIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CachedElasticsearchService cachedElasticsearchService;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private LogElasticsearchSearchService downstreamService;

    @BeforeEach
    void setup() {
        // Flush Redis before every test to ensure isolation
        cacheManager.getCacheNames().forEach(name ->
                java.util.Objects.requireNonNull(cacheManager.getCache(name)).clear()
        );
    }

    @Test
    void shouldSerializeAndDeserializeComplexTypesCorrectly() {
        // This is CRITICAL for Redis. If Jackson config is wrong, Instants become Doubles or Strings.

        // Given
        Instant timestamp = Instant.parse("2025-12-01T10:00:00Z");
        Map<String, Object> complexMetadata = Map.of(
                "userId", 12345,
                "roles", List.of("ADMIN", "USER"),
                "active", true
        );

        LogSearchRequest request = new LogSearchRequest("serialization-test", null, null, null, null, null, 0, 10);

        LogDocument doc = new LogDocument();
        doc.setServiceId("serialization-test");
        doc.setTimestamp(timestamp);
        doc.setMetadata(complexMetadata);
        doc.setLevel(LogStatus.ERROR);
        doc.setPostgresId(500L);

        when(downstreamService.search(any())).thenReturn(
                new PageImpl<>(List.of(doc), PageRequest.of(0, 10), 1)
        );

        // When 1: Cache Miss (Populates Redis)
        cachedElasticsearchService.searchWithCache(request);

        // When 2: Cache Hit (Reads from Redis)
        LogSearchResponse response = cachedElasticsearchService.searchWithCache(request);

        // Then
        var logEntry = response.logs().get(0);

        // Verify Time survived (not just as a string/long, but equal instant)
        assertThat(logEntry.timestamp()).isEqualTo(timestamp);

        // Verify Map structure survived
        assertThat(logEntry.metadata())
                .containsEntry("userId", 12345)
                .containsEntry("active", true);

        // Verify List inside Map survived
        assertThat((List<String>) logEntry.metadata().get("roles")).contains("ADMIN", "USER");    }

    @Test
    void shouldNotCacheEmptyResults() {
        // Given
        LogSearchRequest request = new LogSearchRequest("empty-service", null, null, null, null, null, 0, 10);

        // Mock returning empty list
        when(downstreamService.search(any())).thenReturn(new PageImpl<>(Collections.emptyList()));

        // When: Called twice
        cachedElasticsearchService.searchWithCache(request);
        cachedElasticsearchService.searchWithCache(request);

        // Then: Downstream should be called TWICE.
        // If it was cached, it would be called ONCE.
        verify(downstreamService, times(2)).search(any());
    }

    @Test
    void shouldGenerateDistinctKeysForSimilarRequests() {
        // Scenario: naive key generation might treat "A" + "B" same as "AB" + ""

        // Case 1: Service="ServiceA", Query="QueryB"
        LogSearchRequest req1 = new LogSearchRequest("ServiceA", null, null, null, null, "QueryB", 0, 10);

        // Case 2: Service="ServiceAQueryB", Query=""
        LogSearchRequest req2 = new LogSearchRequest("ServiceAQueryB", null, null, null, null, "", 0, 10);

        when(downstreamService.search(any())).thenReturn(new PageImpl<>(Collections.emptyList()));

        // When
        cachedElasticsearchService.searchWithCache(req1);
        cachedElasticsearchService.searchWithCache(req2);

        // Then: Should be treated as two different cache entries
        verify(downstreamService, times(2)).search(any());
    }
}