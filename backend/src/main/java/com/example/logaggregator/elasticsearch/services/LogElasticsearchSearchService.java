package com.example.logaggregator.elasticsearch.services;

import com.example.logaggregator.elasticsearch.LogDocument;
import com.example.logaggregator.elasticsearch.LogElasticsearchSpecification;
import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchAggregation;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchAggregations;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * FIXED Elasticsearch search service with proper aggregation handling.
 *
 * KEY FIX: Aggregations are now properly extracted and logged.
 * The counts represent the TOTAL across all matching documents, not just the current page.
 */
@Slf4j
@Service
public class LogElasticsearchSearchService {

    private final ElasticsearchOperations elasticsearchOperations;
    private final LogElasticsearchSpecification logElasticsearchSpecification;

    private static final String LEVEL_AGG_NAME = "level_counts";
    private static final String SERVICE_AGG_NAME = "service_counts";

    public LogElasticsearchSearchService(ElasticsearchOperations elasticsearchOperations,
                                         LogElasticsearchSpecification logElasticsearchSpecification) {
        this.elasticsearchOperations = elasticsearchOperations;
        this.logElasticsearchSpecification = logElasticsearchSpecification;
    }

    /**
     * Search with metrics using Elasticsearch aggregations.
     * Aggregations compute totals across ALL matching documents, not just the current page.
     */
    public SearchResultWithMetrics searchWithMetrics(LogSearchRequest request) {
        validateTimeRange(request.startTimestamp(), request.endTimestamp());

        Pageable pageable = PageRequest.of(
                request.page(),
                request.size(),
                Sort.by(Sort.Direction.DESC, "timestamp")
        );

        // Build native query with aggregations
        NativeQuery query = buildNativeQueryWithAggregations(request, pageable);

        long queryStart = System.currentTimeMillis();

        SearchHits<LogDocument> searchHits = elasticsearchOperations.search(
                query,
                LogDocument.class
        );

        long queryTime = System.currentTimeMillis() - queryStart;

        // Extract paginated results
        List<LogDocument> documents = searchHits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());

        long totalHits = searchHits.getTotalHits();

        Page<LogDocument> page = PageableExecutionUtils.getPage(
                documents,
                pageable,
                () -> totalHits
        );

        // Extract aggregation results (computed server-side by Elasticsearch)
        Map<String, Long> levelCounts = new HashMap<>();
        Map<String, Long> serviceCounts = new HashMap<>();

        // CRITICAL: Extract aggregations - these are TOTAL counts, not page counts
        boolean aggregationsExtracted = extractAggregations(searchHits, levelCounts, serviceCounts);

        if (!aggregationsExtracted) {
            log.warn("⚠️ Aggregations not available - counts will be empty. " +
                    "Check Elasticsearch query and index mappings.");
        } else {
            log.info("✅ Aggregations extracted: {} level types, {} services, totalHits={}",
                    levelCounts.size(), serviceCounts.size(), totalHits);
            log.debug("Level counts: {}", levelCounts);
            log.debug("Service counts: {}", serviceCounts);
        }

        log.info("Search completed: page {}/{}, {} results of {} total in {}ms",
                page.getNumber() + 1, page.getTotalPages(),
                page.getNumberOfElements(), page.getTotalElements(), queryTime);

        return new SearchResultWithMetrics(page, levelCounts, serviceCounts);
    }

    /**
     * Build a NativeQuery with both search criteria and aggregations.
     */
    private NativeQuery buildNativeQueryWithAggregations(LogSearchRequest request, Pageable pageable) {
        NativeQueryBuilder builder = NativeQuery.builder();

        // Build the bool query for filtering
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        boolean hasFilters = false;

        // Service ID filter
        if (request.serviceId() != null && !request.serviceId().isBlank()) {
            boolBuilder.filter(QueryBuilders.term(t -> t
                    .field("serviceId")
                    .value(request.serviceId())));
            hasFilters = true;
        }

        // Trace ID filter
        if (request.traceId() != null && !request.traceId().isBlank()) {
            boolBuilder.filter(QueryBuilders.term(t -> t
                    .field("traceId")
                    .value(request.traceId())));
            hasFilters = true;
        }

        // Log level filter
        if (request.level() != null) {
            boolBuilder.filter(QueryBuilders.term(t -> t
                    .field("level")
                    .value(request.level().toString())));
            hasFilters = true;
        }

        // Time range filter
        if (request.startTimestamp() != null && request.endTimestamp() != null) {
            boolBuilder.filter(QueryBuilders.range(r -> r
                    .date(d -> d
                            .field("timestamp")
                            .gte(String.valueOf(request.startTimestamp().toEpochMilli()))
                            .lte(String.valueOf(request.endTimestamp().toEpochMilli()))
                    )
            ));
            hasFilters = true;
        } else if (request.startTimestamp() != null) {
            boolBuilder.filter(QueryBuilders.range(r -> r
                    .date(d -> d
                            .field("timestamp")
                            .gte(String.valueOf(request.startTimestamp().toEpochMilli()))
                    )
            ));
            hasFilters = true;
        } else if (request.endTimestamp() != null) {
            boolBuilder.filter(QueryBuilders.range(r -> r
                    .date(d -> d
                            .field("timestamp")
                            .lte(String.valueOf(request.endTimestamp().toEpochMilli()))
                    )
            ));
            hasFilters = true;
        }

        // Full-text search on message
        if (request.query() != null && !request.query().isBlank()) {
            boolBuilder.must(QueryBuilders.match(m -> m
                    .field("message")
                    .query(request.query())));
            hasFilters = true;
        }

        // Set query
        if (hasFilters) {
            builder.withQuery(boolBuilder.build()._toQuery());
        } else {
            builder.withQuery(QueryBuilders.matchAll().build()._toQuery());
        }

        // CRITICAL: Add aggregations for TOTAL counts across all matching documents
        // These are computed on the full result set, NOT just the current page
        builder.withAggregation(LEVEL_AGG_NAME, Aggregation.of(a -> a
                .terms(t -> t
                        .field("level")
                        .size(10))));

        builder.withAggregation(SERVICE_AGG_NAME, Aggregation.of(a -> a
                .terms(t -> t
                        .field("serviceId")
                        .size(100))));

        // Set pagination and sorting
        builder.withPageable(pageable);
        builder.withSort(Sort.by(Sort.Direction.DESC, "timestamp"));
        builder.withTrackTotalHits(true);

        return builder.build();
    }

    /**
     * Extract aggregation results from SearchHits.
     *
     * @return true if aggregations were successfully extracted, false otherwise
     */
    private boolean extractAggregations(SearchHits<LogDocument> searchHits,
                                        Map<String, Long> levelCounts,
                                        Map<String, Long> serviceCounts) {

        if (!searchHits.hasAggregations()) {
            log.warn("SearchHits.hasAggregations() returned false");
            return false;
        }

        try {
            ElasticsearchAggregations elasticsearchAggregations =
                    (ElasticsearchAggregations) searchHits.getAggregations();

            if (elasticsearchAggregations == null) {
                log.warn("getAggregations() returned null");
                return false;
            }

            List<ElasticsearchAggregation> aggregationList = elasticsearchAggregations.aggregations();

            if (aggregationList == null || aggregationList.isEmpty()) {
                log.warn("Aggregation list is null or empty");
                return false;
            }

            log.debug("Found {} aggregations in response", aggregationList.size());

            boolean foundLevel = false;
            boolean foundService = false;

            for (ElasticsearchAggregation elasticsearchAggregation : aggregationList) {
                String aggName = elasticsearchAggregation.aggregation().getName();
                Aggregate aggregate = elasticsearchAggregation.aggregation().getAggregate();

                log.debug("Processing aggregation: name={}, type={}",
                        aggName, aggregate._kind());

                if (LEVEL_AGG_NAME.equals(aggName)) {
                    foundLevel = extractStringTermsAggregation(aggregate, levelCounts, "level");
                } else if (SERVICE_AGG_NAME.equals(aggName)) {
                    foundService = extractStringTermsAggregation(aggregate, serviceCounts, "service");
                }
            }

            return foundLevel || foundService;

        } catch (Exception e) {
            log.error("Failed to extract aggregations: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Extract bucket counts from a StringTerms aggregation.
     *
     * @return true if extraction was successful
     */
    private boolean extractStringTermsAggregation(Aggregate aggregate,
                                                  Map<String, Long> counts,
                                                  String fieldName) {
        try {
            if (aggregate.isSterms()) {
                StringTermsAggregate sterms = aggregate.sterms();
                List<StringTermsBucket> buckets = sterms.buckets().array();

                log.debug("Extracting {} buckets for {} aggregation", buckets.size(), fieldName);

                for (StringTermsBucket bucket : buckets) {
                    String key = bucket.key().stringValue();
                    long docCount = bucket.docCount();
                    counts.put(key, docCount);
                    log.trace("  {} = {}", key, docCount);
                }

                return !buckets.isEmpty();
            } else {
                log.warn("Aggregation for {} is not StringTerms, got: {}",
                        fieldName, aggregate._kind());
                return false;
            }
        } catch (Exception e) {
            log.error("Could not extract {} aggregation: {}", fieldName, e.getMessage());
            return false;
        }
    }

    /**
     * Original search method without metrics (for backward compatibility).
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

        return PageableExecutionUtils.getPage(
                documents,
                pageable,
                () -> totalHits
        );
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
     * Result object containing page and aggregation metrics.
     */
    public record SearchResultWithMetrics(
            Page<LogDocument> page,
            Map<String, Long> levelCounts,
            Map<String, Long> serviceCounts
    ) {}
}