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
        savedLog.setServiceId("auth-service");
        savedLog.setLevel(LogStatus.INFO);

        when(logRepository.save(any(LogEntry.class)))
                .thenReturn(savedLog);

        LogEntry result = logService.ingest(request);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getServiceId()).isEqualTo("auth-service");
        assertThat(result.getLevel()).isEqualTo(LogStatus.INFO);

        verify(logRepository,times(1)).save(any(LogEntry.class));
    }

    void shouldSaveLogBatchIngestion(){
        LogEntryRequest request1 =  mock(LogEntryRequest.class);
        LogEntryRequest request2 =  mock(LogEntryRequest.class);

        List<LogEntryRequest> logEntries = new ArrayList<>();
        logEntries.add(request1);
        logEntries.add(request2);

        when(logRepository.save(any(LogEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = logService.ingestBatch(logEntries);

        assertThat(result).hasSize(2);
    }
}
