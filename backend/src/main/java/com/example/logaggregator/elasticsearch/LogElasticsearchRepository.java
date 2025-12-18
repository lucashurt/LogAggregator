package com.example.logaggregator.elasticsearch;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.time.Instant;
import java.util.List;

public interface LogElasticsearchRepository extends ElasticsearchRepository<LogDocument, String> {
    List<LogDocument> findByServiceId(String serviceId);

    List<LogDocument> findByTimestampBetween(Instant start, Instant end);

    List<LogDocument> findByTraceId(String traceId);
}
