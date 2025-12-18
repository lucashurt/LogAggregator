package com.example.logaggregator.elasticsearch;

import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import com.example.logaggregator.logs.models.LogStatus;
import com.example.logaggregator.elasticsearch.services.LogElasticsearchSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ElasticsearchSearchServiceTest {

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private LogElasticsearchSpecification logElasticsearchSpecification;

    @InjectMocks
    private LogElasticsearchSearchService logElasticsearchSearchService;

    // ==================== Time Range Validator Tests ====================

    @Test
    void shouldRejectStartTimeAfterEndTime() {
        LogSearchRequest request = new LogSearchRequest(
                null,
                null,
                null,
                Instant.parse("2025-01-02T00:00:00.00Z"),
                Instant.parse("2025-01-01T00:00:00.00Z"),
                null,
                0,
                50
        );

        assertThatThrownBy(() -> logElasticsearchSearchService.search(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Start time cannot be after end time");

        verify(elasticsearchOperations, never()).search(any(Query.class), any(Class.class));
    }

    @Test
    void shouldRejectTimeRangeMoreThan7Days() {
        LogSearchRequest request = new LogSearchRequest(
                null,
                null,
                null,
                Instant.parse("2025-01-01T00:00:00.00Z"),
                Instant.parse("2025-01-09T00:00:00.0Z"),
                null,
                0,
                50
        );

        assertThatThrownBy(() -> logElasticsearchSearchService.search(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Time range cannot be greater than 7 days");

        verify(elasticsearchOperations, never()).search(any(Query.class), any(Class.class));
    }

    @Test
    void shouldAcceptValidTimeRange() {
        LogSearchRequest request = new LogSearchRequest(
                null,
                null,
                null,
                Instant.parse("2025-01-01T00:00:00.00Z"),
                Instant.parse("2025-01-05T00:00:00.00Z"),
                null,
                0,
                50
        );

        // Mock specification to return valid criteria
        Criteria validCriteria = Criteria.where("timestamp")
                .between(Instant.parse("2025-01-01T00:00:00.00Z"),
                        Instant.parse("2025-01-05T00:00:00.00Z"));
        when(logElasticsearchSpecification.buildCriteria(request)).thenReturn(validCriteria);

        SearchHits<LogDocument> mockSearchHits = mock(SearchHits.class);
        when(mockSearchHits.getTotalHits()).thenReturn(0L);
        when(mockSearchHits.getSearchHits()).thenReturn(new ArrayList<>());

        when(elasticsearchOperations.search(any(Query.class), eq(LogDocument.class)))
                .thenReturn(mockSearchHits);

        Page<LogDocument> result = logElasticsearchSearchService.search(request);

        assertThat(result).isNotNull();
        verify(elasticsearchOperations, times(1)).search(any(Query.class), eq(LogDocument.class));
    }

    // ==================== Search Functionality Tests ====================

    @Test
    void shouldSearchLogsSuccessfully() {
        LogSearchRequest request = new LogSearchRequest(
                "auth-service",
                LogStatus.INFO,
                "trace-123",
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-05T00:00:00Z"),
                "logged in",
                0,
                50
        );

        List<SearchHit<LogDocument>> searchHitList = createMockSearchHits(3);
        SearchHits<LogDocument> mockSearchHits = mock(SearchHits.class);
        when(mockSearchHits.getTotalHits()).thenReturn(3L);
        when(mockSearchHits.getSearchHits()).thenReturn(searchHitList);

        // Mock specification to return valid criteria
        Criteria validCriteria = Criteria.where("serviceId").is("auth-service");
        when(logElasticsearchSpecification.buildCriteria(request)).thenReturn(validCriteria);

        when(elasticsearchOperations.search(any(Query.class), eq(LogDocument.class)))
                .thenReturn(mockSearchHits);

        Page<LogDocument> result = logElasticsearchSearchService.search(request);

        assertThat(result.getContent()).hasSize(3);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getNumber()).isEqualTo(0);
        assertThat(result.getSize()).isEqualTo(50);

        verify(elasticsearchOperations, times(1)).search(any(Query.class), eq(LogDocument.class));
    }

    @Test
    void shouldSearchWithNoFilters() {
        LogSearchRequest request = new LogSearchRequest(
                null, null, null, null, null, null, 0, 50
        );

        List<SearchHit<LogDocument>> searchHitList = createMockSearchHits(10);
        SearchHits<LogDocument> mockSearchHits = mock(SearchHits.class);
        when(mockSearchHits.getTotalHits()).thenReturn(10L);
        when(mockSearchHits.getSearchHits()).thenReturn(searchHitList);

        // Mock specification to return empty criteria
        Criteria emptyCriteria = new Criteria();
        when(logElasticsearchSpecification.buildCriteria(request)).thenReturn(emptyCriteria);

        when(elasticsearchOperations.search(any(Query.class), eq(LogDocument.class)))
                .thenReturn(mockSearchHits);

        Page<LogDocument> result = logElasticsearchSearchService.search(request);

        assertThat(result.getContent()).hasSize(10);
        assertThat(result.getTotalElements()).isEqualTo(10);
    }

    @Test
    void shouldReturnEmptyPageWhenNoMatchesFound() {
        LogSearchRequest request = new LogSearchRequest(
                "nonexistent-service",
                LogStatus.ERROR,
                null, null, null, null, 0, 50
        );

        SearchHits<LogDocument> mockSearchHits = mock(SearchHits.class);
        when(mockSearchHits.getTotalHits()).thenReturn(0L);
        when(mockSearchHits.getSearchHits()).thenReturn(new ArrayList<>());

        // Mock specification to return valid criteria
        Criteria validCriteria = Criteria.where("serviceId").is("nonexistent-service");
        when(logElasticsearchSpecification.buildCriteria(request)).thenReturn(validCriteria);

        when(elasticsearchOperations.search(any(Query.class), eq(LogDocument.class)))
                .thenReturn(mockSearchHits);

        Page<LogDocument> result = logElasticsearchSearchService.search(request);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(0);
    }

    @Test
    void shouldApplyCorrectPaginationParameters() {
        LogSearchRequest request = new LogSearchRequest(
                null, null, null, null, null, null, 2, 25
        );

        List<SearchHit<LogDocument>> searchHitList = createMockSearchHits(25);
        SearchHits<LogDocument> mockSearchHits = mock(SearchHits.class);
        when(mockSearchHits.getTotalHits()).thenReturn(100L);
        when(mockSearchHits.getSearchHits()).thenReturn(searchHitList);

        // Mock specification to return empty criteria
        Criteria emptyCriteria = new Criteria();
        when(logElasticsearchSpecification.buildCriteria(request)).thenReturn(emptyCriteria);

        when(elasticsearchOperations.search(any(Query.class), eq(LogDocument.class)))
                .thenReturn(mockSearchHits);

        Page<LogDocument> result = logElasticsearchSearchService.search(request);

        assertThat(result.getNumber()).isEqualTo(2); // Page 2
        assertThat(result.getSize()).isEqualTo(25);  // Size 25
        assertThat(result.getTotalElements()).isEqualTo(100);
        assertThat(result.getTotalPages()).isEqualTo(4);

        verify(elasticsearchOperations, times(1)).search(any(Query.class), eq(LogDocument.class));
    }

    @Test
    void shouldSearchByServiceIdOnly() {
        LogSearchRequest request = new LogSearchRequest(
                "payment-service", null, null, null, null, null, 0, 50
        );

        List<SearchHit<LogDocument>> searchHitList = createMockSearchHits(5);
        SearchHits<LogDocument> mockSearchHits = mock(SearchHits.class);
        when(mockSearchHits.getTotalHits()).thenReturn(5L);
        when(mockSearchHits.getSearchHits()).thenReturn(searchHitList);

        // Mock specification to return valid criteria
        Criteria validCriteria = Criteria.where("serviceId").is("payment-service");
        when(logElasticsearchSpecification.buildCriteria(request)).thenReturn(validCriteria);

        when(elasticsearchOperations.search(any(Query.class), eq(LogDocument.class)))
                .thenReturn(mockSearchHits);

        Page<LogDocument> result = logElasticsearchSearchService.search(request);

        assertThat(result.getContent()).hasSize(5);
        verify(elasticsearchOperations, times(1)).search(any(Query.class), eq(LogDocument.class));
    }

    @Test
    void shouldSearchByTextQuery() {
        LogSearchRequest request = new LogSearchRequest(
                null, null, null, null, null, "timeout error", 0, 50
        );

        List<SearchHit<LogDocument>> searchHitList = createMockSearchHits(3);
        SearchHits<LogDocument> mockSearchHits = mock(SearchHits.class);
        when(mockSearchHits.getTotalHits()).thenReturn(3L);
        when(mockSearchHits.getSearchHits()).thenReturn(searchHitList);

        // Mock specification to return valid criteria - use matches() for text search
        Criteria validCriteria = Criteria.where("message").matches("timeout error");
        when(logElasticsearchSpecification.buildCriteria(request)).thenReturn(validCriteria);

        when(elasticsearchOperations.search(any(Query.class), eq(LogDocument.class)))
                .thenReturn(mockSearchHits);

        Page<LogDocument> result = logElasticsearchSearchService.search(request);

        assertThat(result.getContent()).hasSize(3);
        verify(elasticsearchOperations, times(1)).search(any(Query.class), eq(LogDocument.class));
    }

    // ==================== Helper Methods ====================

    private List<SearchHit<LogDocument>> createMockSearchHits(int count) {
        List<SearchHit<LogDocument>> hits = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            LogDocument doc = new LogDocument();
            doc.setId("es-id-" + i);
            doc.setTimestamp(Instant.parse(String.format("2025-01-%02dT00:00:00Z", i)));
            doc.setServiceId("service-" + i);
            doc.setLevel(LogStatus.INFO);
            doc.setMessage("Test message " + i);
            doc.setTraceId("trace-" + i);
            doc.setCreatedAt(Instant.now());
            doc.setPostgresId((long) i);
            doc.setMetadata(Map.of("key", "value"));

            SearchHit<LogDocument> hit = mock(SearchHit.class);
            when(hit.getContent()).thenReturn(doc);
            hits.add(hit);
        }
        return hits;
    }
}