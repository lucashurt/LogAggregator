package com.example.logaggregator.elasticsearch;

import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import com.example.logaggregator.logs.models.LogEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LogElasticsearchService {
    private final LogElasticsearchRepository logElasticsearchRepository;

    public LogElasticsearchService(LogElasticsearchRepository logElasticsearchRepository) {
        this.logElasticsearchRepository = logElasticsearchRepository;
    }

    public void indexLog(LogEntryRequest logEntryRequest, long postgresId) {
        try{
            LogDocument logDocument = convertToLogDocument(logEntryRequest,postgresId);
            logElasticsearchRepository.save(logDocument);
            log.debug("Indexed log to Elasticsearch: serviceId={}, traceId={}",
                    logEntryRequest.serviceId(), logEntryRequest.traceId());
        }
        catch (Exception e){
            log.error("Failed to index log to Elasticsearch: serviceId={}, error={}",
                    logEntryRequest.serviceId(), e.getMessage());
        }
    }

    public void indexLogBatch(List<LogEntryRequest> requests, List<LogEntry> savedLogs) {
        Map<String,Long> postgresIdMaps = savedLogs.stream()
                .collect(Collectors.toMap(
                        log -> log.getServiceId() + ":" + log.getTimestamp().toString(),
                        LogEntry::getId,
                        (existing,replacement) -> existing
                ));

        List<LogDocument> documents = requests.stream()
                .map(req -> {
                    String key = req.serviceId() + ":" + req.timestamp().toString();
                    Long postgresId = postgresIdMaps.get(key);

                    if (postgresId == null) {
                        log.warn("No PostgreSQL ID found for log: serviceId={}, timestamp={}",
                                req.serviceId(), req.timestamp());
                    }

                    return convertToLogDocument(req,postgresId);
                })
                .collect(Collectors.toList());
    }

    //HELPER FUNCTION
    private LogDocument convertToLogDocument(LogEntryRequest request, Long postgresId) {
        LogDocument logDocument = new LogDocument();
        logDocument.setId(UUID.randomUUID().toString());
        logDocument.setTimestamp(request.timestamp());
        logDocument.setServiceId(request.serviceId());
        logDocument.setLevel(request.level());
        logDocument.setMessage(request.message());
        logDocument.setMetadata(request.metadata());
        logDocument.setTraceId(request.traceId());
        logDocument.setCreatedAt(Instant.now());
        logDocument.setPostgresId(postgresId); // Link to PostgreSQL record
        return logDocument;

    }
}
