package com.example.logaggregator.logs;

import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import com.example.logaggregator.logs.LogEntrySpecification;
import com.example.logaggregator.logs.models.LogEntry;
import com.example.logaggregator.logs.models.LogStatus;
import com.example.logaggregator.logs.services.LogSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class LogSearchServiceTest {
    @Mock
    private LogRepository logRepository;

    @Mock
    private LogEntrySpecification logEntrySpecification;

    @InjectMocks
    private LogSearchService logSearchService;

    // ==================== Time Range Validator Tests ====================

    @Test
    void shouldValidateValidTimeRange(){
        Instant start = Instant.parse("2025-01-01T00:00:00.00Z");
        Instant end = Instant.parse("2025-01-02T00:00:00.00Z");

        logSearchService.validateTimeRange(start, end);
    }

    @Test
    void shouldValidateValidTimeRangeWith7Days(){
        Instant start = Instant.parse("2025-01-01T00:00:00.00Z");
        Instant end = Instant.parse("2025-01-08T00:00:00.00Z");

        logSearchService.validateTimeRange(start, end);
    }

    @Test
    void shouldRejectStartTimeAfterEndTime(){
        Instant start = Instant.parse("2025-01-02T00:00:00.00Z");
        Instant end = Instant.parse("2025-01-01T00:00:00.00Z");

        assertThatThrownBy(() -> logSearchService.validateTimeRange(start, end))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Start time cannot be after end time");
    }

    @Test
    void shouldRejectTimeRangeMoreThan7Days(){
        Instant start = Instant.parse("2025-01-01T00:00:00.00Z");
        Instant end = Instant.parse("2025-01-09T00:00:00.0Z");

        assertThatThrownBy(() -> logSearchService.validateTimeRange(start, end))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Time range cannot be greater than 7 days");
    }
    @Test
    void shouldPassValidationWhenBothTimesAreNull() {
        logSearchService.validateTimeRange(null, null);
    }

    @Test
    void shouldPassValidationWhenOnlyStartTimeIsNull() {
        Instant endTime = Instant.parse("2025-01-05T00:00:00Z");

        logSearchService.validateTimeRange(null, endTime);
    }

    @Test
    void shouldPassValidationWhenOnlyEndTimeIsNull() {
        Instant startTime = Instant.parse("2025-01-01T00:00:00Z");

        logSearchService.validateTimeRange(startTime, null);
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

        List<LogEntry> logEntries = createMockLogEntries(3);
        Page<LogEntry> mockPage = new PageImpl<>(logEntries, PageRequest.of(0, 50), 3);

        Specification<LogEntry> mockSpec = mock(Specification.class);
        when(logEntrySpecification.buildSpecification(request)).thenReturn(mockSpec);
        when(logRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(mockPage);

        Page<LogEntry> result = logSearchService.search(request);

        assertThat(result.getContent()).hasSize(3);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getNumber()).isEqualTo(0);
        assertThat(result.getSize()).isEqualTo(50);

        verify(logEntrySpecification, times(1)).buildSpecification(request);
        verify(logRepository, times(1)).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void shouldSearchWithNoFilters() {
        LogSearchRequest request = new LogSearchRequest(
                null, null, null, null, null, null, 0, 50
        );

        List<LogEntry> logEntries = createMockLogEntries(10);
        Page<LogEntry> mockPage = new PageImpl<>(logEntries, PageRequest.of(0, 50), 10);

        Specification<LogEntry> mockSpec = mock(Specification.class);
        when(logEntrySpecification.buildSpecification(request)).thenReturn(mockSpec);
        when(logRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(mockPage);

        Page<LogEntry> result = logSearchService.search(request);

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

        Page<LogEntry> emptyPage = new PageImpl<>(new ArrayList<>(), PageRequest.of(0, 50), 0);

        Specification<LogEntry> mockSpec = mock(Specification.class);
        when(logEntrySpecification.buildSpecification(request)).thenReturn(mockSpec);
        when(logRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(emptyPage);

        Page<LogEntry> result = logSearchService.search(request);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(0);
    }

    @Test
    void shouldApplyCorrectPaginationParameters() {
        LogSearchRequest request = new LogSearchRequest(
                null, null, null, null, null, null, 2, 25
        );

        List<LogEntry> logEntries = createMockLogEntries(25);
        Page<LogEntry> mockPage = new PageImpl<>(logEntries, PageRequest.of(2, 25), 100);

        Specification<LogEntry> mockSpec = mock(Specification.class);
        when(logEntrySpecification.buildSpecification(request)).thenReturn(mockSpec);
        when(logRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(mockPage);

        Page<LogEntry> result = logSearchService.search(request);

        assertThat(result.getNumber()).isEqualTo(2); // Page 2
        assertThat(result.getSize()).isEqualTo(25);  // Size 25
        assertThat(result.getTotalElements()).isEqualTo(100);
        assertThat(result.getTotalPages()).isEqualTo(4);

        verify(logRepository).findAll(
                ArgumentMatchers.<Specification<LogEntry>>any(),
                ArgumentMatchers.<Pageable>argThat(pageable ->
                        pageable.getPageNumber() == 2 &&
                                pageable.getPageSize() == 25 &&
                                pageable.getSort().getOrderFor("timestamp").getDirection() == Sort.Direction.DESC
                )
        );
    }

    @Test
    void shouldSortByTimestampDescending() {
        LogSearchRequest request = new LogSearchRequest(
                null, null, null, null, null, null, 0, 50
        );

        List<LogEntry> logEntries = createMockLogEntries(3);
        Page<LogEntry> mockPage = new PageImpl<>(logEntries, PageRequest.of(0, 50), 3);

        Specification<LogEntry> mockSpec = mock(Specification.class);
        when(logEntrySpecification.buildSpecification(request)).thenReturn(mockSpec);
        when(logRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(mockPage);

        logSearchService.search(request);

        verify(logRepository).findAll(
                ArgumentMatchers.<Specification<LogEntry>>any(),
                ArgumentMatchers.<Pageable>argThat(pageable -> {
                    Sort.Order order = pageable.getSort().getOrderFor("timestamp");
                    return order != null && order.getDirection() == Sort.Direction.DESC;
                })
        );
    }

    @Test
    void shouldThrowExceptionWhenSearchFailsTimeValidation() {
        LogSearchRequest request = new LogSearchRequest(
                null,
                null,
                null,
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-20T00:00:00Z"),
                null,
                0,
                50
        );

        assertThatThrownBy(() -> logSearchService.search(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Time range cannot be greater than 7 days");

        verify(logRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void shouldSearchByServiceIdOnly() {
        LogSearchRequest request = new LogSearchRequest(
                "payment-service", null, null, null, null, null, 0, 50
        );

        List<LogEntry> logEntries = createMockLogEntries(5);
        Page<LogEntry> mockPage = new PageImpl<>(logEntries, PageRequest.of(0, 50), 5);

        Specification<LogEntry> mockSpec = mock(Specification.class);
        when(logEntrySpecification.buildSpecification(request)).thenReturn(mockSpec);
        when(logRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(mockPage);

        Page<LogEntry> result = logSearchService.search(request);

        assertThat(result.getContent()).hasSize(5);
        verify(logEntrySpecification).buildSpecification(argThat(req ->
                req.serviceId().equals("payment-service") &&
                        req.level() == null &&
                        req.traceId() == null
        ));
    }

    @Test
    void shouldSearchByTraceIdOnly() {
        LogSearchRequest request = new LogSearchRequest(
                null, null, "trace-456", null, null, null, 0, 50
        );

        List<LogEntry> logEntries = createMockLogEntries(2);
        Page<LogEntry> mockPage = new PageImpl<>(logEntries, PageRequest.of(0, 50), 2);

        Specification<LogEntry> mockSpec = mock(Specification.class);
        when(logEntrySpecification.buildSpecification(request)).thenReturn(mockSpec);
        when(logRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(mockPage);

        Page<LogEntry> result = logSearchService.search(request);

        assertThat(result.getContent()).hasSize(2);
        verify(logEntrySpecification).buildSpecification(argThat(req ->
                req.traceId().equals("trace-456") &&
                        req.level() == null &&
                        req.serviceId() == null
        ));
    }

    // ==================== Helper Methods ====================

    private List<LogEntry> createMockLogEntries(int count) {
        List<LogEntry> entries = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            LogEntry entry = new LogEntry();
            entry.setId((long) i);
            entry.setTimestamp(Instant.parse(String.format("2025-01-%02dT00:00:00Z", i)));
            entry.setServiceId("service-" + i);
            entry.setLevel(LogStatus.INFO);
            entry.setMessage("Test message " + i);
            entry.setTraceId("trace-" + i);
            entry.setCreatedAt(Instant.now());
            entries.add(entry);
        }
        return entries;
    }
}