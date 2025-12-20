package com.example.logaggregator.redis;

import com.example.logaggregator.elasticsearch.LogDocument;
import com.example.logaggregator.elasticsearch.services.LogElasticsearchSearchService;
import com.example.logaggregator.logs.DTOs.LogEntryResponse;
import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import com.example.logaggregator.logs.DTOs.LogSearchResponse;
import com.example.logaggregator.logs.models.LogStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CachedElasticsearchServiceTest {

    @Mock
    private LogElasticsearchSearchService logElasticsearchSearchService;

    @InjectMocks
    private CachedElasticsearchService cachedElasticsearchService;

    @Test
    @DisplayName("Should map ALL fields correctly from LogDocument to LogEntryResponse")
    void shouldMapAllFieldsCorrectly() {
        // Given
        Instant now = Instant.now();
        String traceId = UUID.randomUUID().toString();
        Map<String, Object> metadata = Map.of("browser", "Chrome", "latency", 45);

        LogDocument doc = new LogDocument();
        doc.setId("es-id-xyz");
        doc.setPostgresId(999L); // Important: Verifying PostgresID mapping
        doc.setServiceId("auth-service");
        doc.setMessage("User login attempt");
        doc.setLevel(LogStatus.WARNING);
        doc.setTimestamp(now);
        doc.setTraceId(traceId);
        doc.setMetadata(metadata);
        doc.setCreatedAt(now.minusSeconds(10));

        LogSearchRequest request = new LogSearchRequest("auth-service", null, null, null, null, null, 0, 10);
        when(logElasticsearchSearchService.search(request))
                .thenReturn(new PageImpl<>(List.of(doc), PageRequest.of(0, 10), 1));

        // When
        LogSearchResponse response = cachedElasticsearchService.searchWithCache(request);

        // Then
        assertThat(response.logs()).hasSize(1);
        LogEntryResponse result = response.logs().get(0);

        // rigorous field-by-field assertion
        assertThat(result.id()).as("Postgres ID mismatch").isEqualTo(999L);
        assertThat(result.serviceId()).isEqualTo("auth-service");
        assertThat(result.message()).isEqualTo("User login attempt");
        assertThat(result.level()).isEqualTo(LogStatus.WARNING);
        assertThat(result.timestamp()).isEqualTo(now);
        assertThat(result.traceId()).isEqualTo(traceId);
        assertThat(result.metadata()).containsEntry("browser", "Chrome");

        verify(logElasticsearchSearchService).search(request);
    }

    @Test
    @DisplayName("Should propagate exceptions from underlying service")
    void shouldPropagateExceptions() {
        // Given
        LogSearchRequest request = new LogSearchRequest("fail-service", null, null, null, null, null, 0, 10);
        when(logElasticsearchSearchService.search(request)).thenThrow(new RuntimeException("Elasticsearch is down"));

        // When/Then
        assertThatThrownBy(() -> cachedElasticsearchService.searchWithCache(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Elasticsearch is down");
    }

    @Test
    @DisplayName("searchWithoutCache should explicitly bypass any internal caching logic")
    void shouldSearchWithoutCache() {
        // Given
        LogSearchRequest request = new LogSearchRequest("realtime-service", null, null, null, null, null, 0, 10);
        when(logElasticsearchSearchService.search(request))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        // When
        cachedElasticsearchService.searchWithoutCache(request);

        // Then
        verify(logElasticsearchSearchService).search(request);
    }
}