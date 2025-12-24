package com.example.logaggregator.elasticsearch.services;

import com.example.logaggregator.elasticsearch.LogDocument;
import com.example.logaggregator.elasticsearch.LogElasticsearchSpecification;
import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import com.example.logaggregator.logs.models.LogStatus;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LogElasticsearchSearchService {

    private final ElasticsearchOperations elasticsearchOperations;
    private final LogElasticsearchSpecification logElasticsearchSpecification;

    public LogElasticsearchSearchService(ElasticsearchOperations elasticsearchOperations,
                                         LogElasticsearchSpecification logElasticsearchSpecification) {
        this.elasticsearchOperations = elasticsearchOperations;
        this.logElasticsearchSpecification = logElasticsearchSpecification;
    }

    /**
     * Enhanced search with metrics calculated from a larger sample
     * This approach avoids complex native query syntax issues
     */
    public SearchResultWithMetrics searchWithMetrics(LogSearchRequest request) {
        validateTimeRange(request.startTimestamp(), request.endTimestamp());

        // Build criteria using existing specification
        Criteria criteria = logElasticsearchSpecification.buildCriteria(request);

        // Create pageable with sorting for the requested page
        Pageable pageable = PageRequest.of(
                request.page(),
                request.size(),
                Sort.by(Sort.Direction.DESC, "timestamp")
        );

        // Execute main search for paginated results (using existing approach)
        Query searchQuery;
        if (hasNoCriteria(request)) {
            searchQuery = Query.findAll();
            searchQuery.setPageable(pageable);
        } else {
            searchQuery = new CriteriaQuery(criteria);
            searchQuery.setPageable(pageable);
        }
        searchQuery.setTrackTotalHits(true);

        SearchHits<LogDocument> searchHits = elasticsearchOperations.search(
                searchQuery,
                LogDocument.class
        );

        // Convert SearchHits to Page
        List<LogDocument> documents = searchHits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());

        long totalHits = searchHits.getTotalHits();

        Page<LogDocument> page = PageableExecutionUtils.getPage(
                documents,
                pageable,
                () -> totalHits
        );

        // Calculate metrics from a larger sample (up to 1000 results)
        // This gives us accurate-enough metrics without complex aggregation queries
        Map<String, Long> levelCounts = new HashMap<>();
        Map<String, Long> serviceCounts = new HashMap<>();

        try {
            // Fetch up to 1000 results to calculate metrics
            // For most use cases, this gives representative statistics
            Pageable metricsSample = PageRequest.of(0, 1000, Sort.by(Sort.Direction.DESC, "timestamp"));

            Query metricsQuery;
            if (hasNoCriteria(request)) {
                metricsQuery = Query.findAll();
                metricsQuery.setPageable(metricsSample);
            } else {
                metricsQuery = new CriteriaQuery(criteria);
                metricsQuery.setPageable(metricsSample);
            }

            SearchHits<LogDocument> metricsHits = elasticsearchOperations.search(
                    metricsQuery,
                    LogDocument.class
            );

            List<LogDocument> metricsDocuments = metricsHits.getSearchHits().stream()
                    .map(SearchHit::getContent)
                    .toList();

            // Calculate level counts
            for (LogDocument doc : metricsDocuments) {
                String level = doc.getLevel().toString();
                levelCounts.put(level, levelCounts.getOrDefault(level, 0L) + 1);
            }

            // Calculate service counts
            for (LogDocument doc : metricsDocuments) {
                String service = doc.getServiceId();
                serviceCounts.put(service, serviceCounts.getOrDefault(service, 0L) + 1);
            }

            // If we have all results (totalHits <= 1000), metrics are exact
            // Otherwise, they're based on a representative sample
            if (totalHits > 1000) {
                log.info("Metrics calculated from sample of 1000 out of {} total results", totalHits);
            }

        } catch (Exception e) {
            log.warn("Failed to calculate metrics: {}", e.getMessage());
            // Continue without metrics rather than failing the whole request
        }

        log.info("Elasticsearch search executed: found {} results out of {} total",
                page.getNumberOfElements(),
                page.getTotalElements());

        return new SearchResultWithMetrics(page, levelCounts, serviceCounts);
    }

    /**
     * Original search method without metrics (for backward compatibility)
     */
    public Page<LogDocument> search(LogSearchRequest request) {
        validateTimeRange(request.startTimestamp(), request.endTimestamp());

        Criteria criteria = logElasticsearchSpecification.buildCriteria(request);

        Pageable pageable = PageRequest.of(
                request.page(),
                request.size(),
                Sort.by(Sort.Direction.DESC, "timestamp")
        );

        Query query;
        if (hasNoCriteria(request)) {
            query = Query.findAll();
            query.setPageable(pageable);
        } else {
            query = new CriteriaQuery(criteria);
            query.setPageable(pageable);
        }

        SearchHits<LogDocument> searchHits = elasticsearchOperations.search(
                query,
                LogDocument.class
        );

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

    /**
     * Result object containing page and aggregation metrics
     */
    public record SearchResultWithMetrics(
            Page<LogDocument> page,
            Map<String, Long> levelCounts,
            Map<String, Long> serviceCounts
    ) {}
}