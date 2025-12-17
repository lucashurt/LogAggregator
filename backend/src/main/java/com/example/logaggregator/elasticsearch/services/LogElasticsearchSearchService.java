package com.example.logaggregator.elasticsearch.services;

import com.example.logaggregator.elasticsearch.LogDocument;
import com.example.logaggregator.elasticsearch.LogElasticsearchSpecification;
import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LogElasticsearchSearchService {

    private final ElasticsearchOperations elasticsearchOperations;
    private final LogElasticsearchSpecification logElasticsearchSpecification;
    public LogElasticsearchSearchService(ElasticsearchOperations elasticsearchOperations, LogElasticsearchSpecification logElasticsearchSpecification) {
        this.elasticsearchOperations = elasticsearchOperations;
        this.logElasticsearchSpecification = logElasticsearchSpecification;
    }

    /**
     * Search logs using Elasticsearch Criteria API.
     */
    public Page<LogDocument> search(LogSearchRequest request) {
        // Validate time range
        validateTimeRange(request.startTimestamp(), request.endTimestamp());

        // Build criteria
        Criteria criteria = logElasticsearchSpecification.buildCriteria(request);

        // Create pageable with sorting
        Pageable pageable = PageRequest.of(
                request.page(),
                request.size(),
                Sort.by(Sort.Direction.DESC, "timestamp")
        );

        Query query;
        // Create CriteriaQuery (NOT NativeQuery)
        if (hasNoCriteria(request)) {
            // Use a simple query that matches all documents when no filters specified
            query = Query.findAll();
            query.setPageable(pageable);
        } else {
            // Create CriteriaQuery with actual criteria
            query = new CriteriaQuery(criteria);
            query.setPageable(pageable);
        }

        // Execute search
        SearchHits<LogDocument> searchHits = elasticsearchOperations.search(
                query,
                LogDocument.class
        );

        // Convert SearchHits to Page<LogDocument>
        List<LogDocument> documents = searchHits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());

        long totalHits = searchHits.getTotalHits();

        Page<LogDocument> page = PageableExecutionUtils.getPage(
                documents,
                pageable,
                () -> totalHits
        );

        log.info("Elasticsearch search executed: found {} results out of {} total",
                page.getNumberOfElements(),
                page.getTotalElements());

        return page;
    }


    private boolean hasNoCriteria(LogSearchRequest request) {
        return (request.serviceId() == null || request.serviceId().isBlank()) &&
                (request.traceId() == null || request.traceId().isBlank()) &&
                request.level() == null &&
                request.startTimestamp() == null &&
                request.endTimestamp() == null &&
                (request.query() == null || request.query().isBlank());
    }

    /**
     * Validate time range (same logic as PostgreSQL service).
     */
    private void validateTimeRange(Instant startTime, Instant endTime) {
        if (startTime != null && endTime != null) {
            long daysBetween = Duration.between(startTime, endTime).toDays();

            if (daysBetween > 7) {
                throw new IllegalArgumentException(
                        "Time range cannot be greater than 7 days"
                );
            }

            if (startTime.isAfter(endTime)) {
                throw new IllegalArgumentException(
                        "Start time cannot be after end time"
                );
            }
        }
    }
}
