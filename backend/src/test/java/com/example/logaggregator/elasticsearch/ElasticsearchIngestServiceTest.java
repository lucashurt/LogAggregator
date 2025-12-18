package com.example.logaggregator.elasticsearch;

import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import com.example.logaggregator.logs.models.LogEntry;
import com.example.logaggregator.logs.models.LogStatus;
import com.example.logaggregator.elasticsearch.services.LogElasticsearchIngestService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ElasticsearchIngestServiceTest {

    @Mock
    private LogElasticsearchRepository logElasticsearchRepository;

    @InjectMocks
    private LogElasticsearchIngestService logElasticsearchIngestService;

    @Test
    void shouldIndexLogSuccessfully() {
        LogEntryRequest request = new LogEntryRequest(
                Instant.parse("2025-01-01T00:00:00Z"),
                "auth-service",
                LogStatus.INFO,
                "User logged in",
                Map.of("ip", "127.0.0.1"),
                "trace-123"
        );

        when(logElasticsearchRepository.save(any(LogDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        logElasticsearchIngestService.indexLog(request, 1L);

        verify(logElasticsearchRepository, times(1)).save(any(LogDocument.class));
    }

    @Test
    void shouldHandleIndexingErrorGracefully() {
        LogEntryRequest request = new LogEntryRequest(
                Instant.parse("2025-01-01T00:00:00Z"),
                "auth-service",
                LogStatus.INFO,
                "User logged in",
                Map.of("ip", "127.0.0.1"),
                "trace-123"
        );

        when(logElasticsearchRepository.save(any(LogDocument.class)))
                .thenThrow(new RuntimeException("Elasticsearch connection failed"));

        // Should not throw exception - error is logged
        logElasticsearchIngestService.indexLog(request, 1L);

        verify(logElasticsearchRepository, times(1)).save(any(LogDocument.class));
    }

    @Test
    void shouldIndexBatchSuccessfully() {
        List<LogEntryRequest> requests = new ArrayList<>();
        requests.add(new LogEntryRequest(
                Instant.parse("2025-01-01T00:00:00Z"),
                "auth-service",
                LogStatus.INFO,
                "User logged in",
                Map.of("ip", "127.0.0.1"),
                "trace-123"
        ));
        requests.add(new LogEntryRequest(
                Instant.parse("2025-01-01T01:00:00Z"),
                "payment-service",
                LogStatus.ERROR,
                "Payment failed",
                Map.of("amount", "100"),
                "trace-456"
        ));

        List<LogEntry> savedLogs = new ArrayList<>();
        LogEntry entry1 = new LogEntry();
        entry1.setId(1L);
        entry1.setServiceId("auth-service");
        entry1.setTimestamp(Instant.parse("2025-01-01T00:00:00Z"));
        savedLogs.add(entry1);

        LogEntry entry2 = new LogEntry();
        entry2.setId(2L);
        entry2.setServiceId("payment-service");
        entry2.setTimestamp(Instant.parse("2025-01-01T01:00:00Z"));
        savedLogs.add(entry2);

        when(logElasticsearchRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        logElasticsearchIngestService.indexLogBatch(requests, savedLogs);

        verify(logElasticsearchRepository, times(1)).saveAll(anyList());
    }

    @Test
    void shouldHandleBatchIndexingErrorGracefully() {
        List<LogEntryRequest> requests = new ArrayList<>();
        requests.add(new LogEntryRequest(
                Instant.parse("2025-01-01T00:00:00Z"),
                "auth-service",
                LogStatus.INFO,
                "User logged in",
                Map.of("ip", "127.0.0.1"),
                "trace-123"
        ));

        List<LogEntry> savedLogs = new ArrayList<>();
        LogEntry entry = new LogEntry();
        entry.setId(1L);
        entry.setServiceId("auth-service");
        entry.setTimestamp(Instant.parse("2025-01-01T00:00:00Z"));
        savedLogs.add(entry);

        when(logElasticsearchRepository.saveAll(anyList()))
                .thenThrow(new RuntimeException("Batch indexing failed"));

        // Should not throw exception - error is logged
        logElasticsearchIngestService.indexLogBatch(requests, savedLogs);

        verify(logElasticsearchRepository, times(1)).saveAll(anyList());
    }

    @Test
    void shouldHandleMissingPostgresIdInBatch() {
        List<LogEntryRequest> requests = new ArrayList<>();
        requests.add(new LogEntryRequest(
                Instant.parse("2025-01-01T00:00:00Z"),
                "auth-service",
                LogStatus.INFO,
                "User logged in",
                Map.of("ip", "127.0.0.1"),
                "trace-123"
        ));

        // Empty savedLogs - no PostgreSQL IDs available
        List<LogEntry> savedLogs = new ArrayList<>();

        when(logElasticsearchRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        logElasticsearchIngestService.indexLogBatch(requests, savedLogs);

        verify(logElasticsearchRepository, times(1)).saveAll(anyList());
    }
}