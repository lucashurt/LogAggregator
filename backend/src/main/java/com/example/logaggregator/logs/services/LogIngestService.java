package com.example.logaggregator.logs.services;

import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import com.example.logaggregator.logs.LogRepository;
import com.example.logaggregator.logs.models.LogEntry;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
public class LogIngestService {
    private final LogRepository logRepository;

    public LogIngestService(LogRepository logRepository) {
        this.logRepository = logRepository;
    }

    public LogEntry ingest(LogEntryRequest request){
        LogEntry logEntry = new LogEntry();
        logEntry.setTimestamp(request.timestamp());
        logEntry.setServiceId(request.serviceId());
        logEntry.setLevel(request.level());
        logEntry.setMessage(request.message());
        logEntry.setMetadata(request.metadata());
        logEntry.setTraceId(request.traceId());
        logEntry.setCreatedAt(Instant.now());

        return logRepository.save(logEntry);
    }

    @Transactional
    public List<LogEntry> ingestBatch(List<LogEntryRequest> requests) {
        List<LogEntry> entities = requests.stream()
                .map(req -> {
                    LogEntry entry = new LogEntry();
                    entry.setTimestamp(req.timestamp());
                    entry.setServiceId(req.serviceId());
                    entry.setLevel(req.level());
                    entry.setMessage(req.message());
                    entry.setMetadata(req.metadata());
                    entry.setTraceId(req.traceId());
                    entry.setCreatedAt(Instant.now());
                    return entry;
                })
                .toList();

        return logRepository.saveAll(entities);
    }
}
