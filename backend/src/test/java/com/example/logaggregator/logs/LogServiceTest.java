package com.example.logaggregator.logs;

import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import com.example.logaggregator.logs.models.LogEntry;
import com.example.logaggregator.logs.models.LogStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
public class LogServiceTest {

    @Mock
    private LogRepository logRepository;
    @InjectMocks
    private LogService logService;

    @Test
    void shouldSaveLogIngestion(){
        LogEntryRequest request =  new LogEntryRequest(
                Instant.parse("2025-01-01T00:00:00Z"),
                "auth-service",
                LogStatus.INFO,
                "User Logged In",
                Map.of("ip", "127.0.0.1"),
                "trace-123"
        );

        LogEntry savedLog = new LogEntry();
        savedLog.setId(1L);
        savedLog.setTimestamp(Instant.parse("2025-01-01T00:00:00Z"));
        savedLog.setServiceId("auth-service");
        savedLog.setLevel(LogStatus.INFO);
        savedLog.setMessage("User Logged In");
        savedLog.setTraceId("trace-123");
        savedLog.setMetadata(Map.of("ip", "127.0.0.1"));
        savedLog.setCreatedAt(Instant.now());

        when(logRepository.save(any(LogEntry.class)))
                .thenReturn(savedLog);

        LogEntry result = logService.ingest(request);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getServiceId()).isEqualTo("auth-service");
        assertThat(result.getLevel()).isEqualTo(LogStatus.INFO);
        assertThat(result.getMessage()).isEqualTo("User Logged In");
        assertThat(result.getTraceId()).isEqualTo("trace-123");
        assertThat(result.getTimestamp()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
        assertThat(result.getMetadata()).containsEntry("ip", "127.0.0.1");
        assertThat(result.getCreatedAt()).isNotNull();

        verify(logRepository,times(1)).save(any(LogEntry.class));
    }

    @Test
    void shouldSaveLogBatchIngestion(){
        LogEntryRequest request1 =  new LogEntryRequest(
                Instant.parse("2025-01-01T00:00:00Z"),
                "auth-service",
                LogStatus.INFO,
                "User Logged In",
                Map.of("ip", "127.0.0.1"),
                "trace-123"
        );

        LogEntryRequest request2 =  new LogEntryRequest(
                Instant.parse("2025-01-01T00:00:00Z"),
                "payment-service",
                LogStatus.ERROR,
                "Payment Failed",
                Map.of("amount", "0"),
                "trace-456"
        );

        List<LogEntryRequest> logEntries = new ArrayList<>();
        logEntries.add(request1);
        logEntries.add(request2);

        when(logRepository.save(any(LogEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = logService.ingestBatch(logEntries);

        assertThat(result).hasSize(2);
        verify(logRepository,times(2)).save(any(LogEntry.class));
    }
}
