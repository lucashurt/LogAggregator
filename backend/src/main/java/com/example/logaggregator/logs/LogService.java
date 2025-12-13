package com.example.logaggregator.logs;

import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import com.example.logaggregator.logs.models.LogEntry;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
public class LogService {
    private final  LogRepository logRepository;
    public LogService(LogRepository logRepository) {
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

        LogEntry saved =  logRepository.save(logEntry);
        log.info("Ingested log: id={}, service={}, level={}",
                saved.getId(), saved.getServiceId(), saved.getLevel());
        return saved;
    }

    @Transactional
    public List<LogEntry> ingestBatch(List<LogEntryRequest> requests){
        return requests.
                stream()
                .map(this::ingest)
                .toList();

    }
}
