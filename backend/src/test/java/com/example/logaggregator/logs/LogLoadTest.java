package com.example.logaggregator.logs;

import com.example.logaggregator.BaseIntegrationTest;
import com.example.logaggregator.elasticsearch.LogDocument;
import com.example.logaggregator.elasticsearch.LogElasticsearchRepository;
import com.example.logaggregator.elasticsearch.services.LogElasticsearchIngestService;
import com.example.logaggregator.elasticsearch.services.LogElasticsearchSearchService;
import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import com.example.logaggregator.logs.models.LogEntry;
import com.example.logaggregator.logs.models.LogStatus;
import com.example.logaggregator.logs.services.LogIngestService;
import com.example.logaggregator.logs.services.LogPostgresSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.utility.TestcontainersConfiguration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public class LogLoadTest extends BaseIntegrationTest {

    @Autowired
    private LogIngestService logIngestService;

    @Autowired
    private LogPostgresSearchService postgresSearchService;

    @Autowired
    private LogElasticsearchSearchService elasticsearchSearchService;

    @Autowired
    private LogElasticsearchIngestService elasticsearchIngestService;

    @Autowired
    private LogRepository logRepository;

    @Autowired
    private LogElasticsearchRepository elasticsearchRepository;

    private final Random random = new Random();
    private final String[] services = {"auth-service", "payment-service", "notification-service", "user-service"};
    private final LogStatus[] levels = {LogStatus.INFO, LogStatus.DEBUG, LogStatus.WARNING, LogStatus.ERROR};
    private final String[] messages = {
            "User logged in successfully",
            "Payment processed",
            "Database connection timeout",
            "Cache miss",
            "API request received",
            "Authentication failed",
            "Transaction completed",
            "Error processing request"
    };

    @BeforeEach
    void cleanup() {
        // Clean both databases before each test
        logRepository.deleteAll();
        elasticsearchRepository.deleteAll();
    }

    @Test
    void shouldIngest1000LogsInBatch() {
        // Arrange
        List<LogEntryRequest> requests = generate1000Logs();

        // Act
        long startTime = System.currentTimeMillis();
        List<LogEntry> results = logIngestService.ingestBatch(requests);
        long endTime = System.currentTimeMillis();

        // Assert
        assertThat(results).hasSize(1000);

        long totalCount = logRepository.count();
        assertThat(totalCount).isEqualTo(1000);

        long duration = endTime - startTime;
        System.out.println("===========================================");
        System.out.println("BATCH INGESTION TEST RESULTS");
        System.out.println("===========================================");
        System.out.println("Total logs ingested: 1000");
        System.out.println("Time taken: " + duration + "ms");
        System.out.println("Average per log: " + (duration / 1000.0) + "ms");
        System.out.println("Throughput: " + (1000.0 / duration * 1000) + " logs/second");
        System.out.println("===========================================");

        // Performance assertion - should complete in reasonable time
        assertThat(duration).isLessThan(10000); // Should complete within 10 seconds
    }

    @Test
    void shouldIngest1000LogsIndividually() {
        // Arrange
        List<LogEntryRequest> requests = generate1000Logs();

        // Act
        long startTime = System.currentTimeMillis();
        for (LogEntryRequest request : requests) {
            logIngestService.ingest(request);
        }
        long endTime = System.currentTimeMillis();

        // Assert
        long totalCount = logRepository.count();
        assertThat(totalCount).isEqualTo(1000);

        long duration = endTime - startTime;
        System.out.println("===========================================");
        System.out.println("INDIVIDUAL INGESTION TEST RESULTS");
        System.out.println("===========================================");
        System.out.println("Total logs ingested: 1000");
        System.out.println("Time taken: " + duration + "ms");
        System.out.println("Average per log: " + (duration / 1000.0) + "ms");
        System.out.println("Throughput: " + (1000.0 / duration * 1000) + " logs/second");
        System.out.println("===========================================");
    }

    @Test
    void shouldCompareElasticsearchVsPostgresSearchPerformance() {
        // Arrange - Ingest 1000 logs to both PostgreSQL and Elasticsearch
        List<LogEntryRequest> requests = generate1000Logs();
        List<LogEntry> savedLogs = logIngestService.ingestBatch(requests);
        elasticsearchIngestService.indexLogBatch(requests, savedLogs);

        // Wait for Elasticsearch to finish indexing
        try {
            Thread.sleep(2000); // Give Elasticsearch time to index
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Define various search scenarios
        LogSearchRequest[] searchRequests = {
                // Search by service
                new LogSearchRequest("auth-service", null, null, null, null, null, 0, 50),

                // Search by level
                new LogSearchRequest(null, LogStatus.ERROR, null, null, null, null, 0, 50),

                // Search by time range
                new LogSearchRequest(null, null, null,
                        Instant.now().minus(Duration.ofHours(1)),
                        Instant.now(),
                        null, 0, 50),

                // Search by text
                new LogSearchRequest(null, null, null, null, null, "timeout", 0, 50),

                // Combined search
                new LogSearchRequest("payment-service", LogStatus.INFO, null, null, null, null, 0, 50)
        };

        System.out.println("===========================================");
        System.out.println("ELASTICSEARCH VS POSTGRESQL COMPARISON");
        System.out.println("===========================================");
        System.out.println(String.format("%-40s | %-15s | %-15s | %-10s",
                "Search Type", "PostgreSQL (ms)", "Elasticsearch (ms)", "Speedup"));
        System.out.println("-------------------------------------------+------------------+-------------------+-----------");

        String[] searchTypes = {
                "Service Filter (auth-service)",
                "Level Filter (ERROR)",
                "Time Range (1 hour)",
                "Text Search (timeout)",
                "Combined (service + level)"
        };

        long totalPostgresTime = 0;
        long totalElasticsearchTime = 0;

        // Execute and compare each search
        for (int i = 0; i < searchRequests.length; i++) {
            // PostgreSQL search
            long pgStartTime = System.currentTimeMillis();
            Page<LogEntry> pgResults = postgresSearchService.search(searchRequests[i]);
            long pgDuration = System.currentTimeMillis() - pgStartTime;
            totalPostgresTime += pgDuration;

            // Elasticsearch search
            long esStartTime = System.currentTimeMillis();
            Page<LogDocument> esResults = elasticsearchSearchService.search(searchRequests[i]);
            long esDuration = System.currentTimeMillis() - esStartTime;
            totalElasticsearchTime += esDuration;

            // Calculate speedup
            double speedup = (double) pgDuration / esDuration;

            System.out.println(String.format("%-40s | %-15d | %-15d | %.2fx",
                    searchTypes[i], pgDuration, esDuration, speedup));

            // Verify results consistency
            assertThat(pgResults.getTotalElements())
                    .as("Results count should match between PostgreSQL and Elasticsearch for: " + searchTypes[i])
                    .isEqualTo(esResults.getTotalElements());

            // Performance assertions
            assertThat(pgDuration).isLessThan(1000); // PostgreSQL should complete within 1 second
            assertThat(esDuration).isLessThan(500);  // Elasticsearch should be faster
        }

        double avgSpeedup = (double) totalPostgresTime / totalElasticsearchTime;

        System.out.println("-------------------------------------------+------------------+-------------------+-----------");
        System.out.println(String.format("%-40s | %-15d | %-15d | %.2fx",
                "TOTAL", totalPostgresTime, totalElasticsearchTime, avgSpeedup));
        System.out.println("===========================================");

        // Elasticsearch should be faster on average
        assertThat(totalElasticsearchTime).isLessThan(totalPostgresTime);
    }

    @Test
    void shouldCompareFullTextSearchPerformance() {
        // Arrange - Create logs with specific text patterns
        List<LogEntryRequest> requests = new ArrayList<>();
        Instant baseTime = Instant.now().minus(Duration.ofHours(1));

        for (int i = 0; i < 1000; i++) {
            String message = i % 10 == 0
                    ? "Critical database connection timeout error occurred"
                    : "Normal operation message " + i;

            requests.add(new LogEntryRequest(
                    baseTime.plus(Duration.ofSeconds(i)),
                    services[random.nextInt(services.length)],
                    levels[random.nextInt(levels.length)],
                    message,
                    Map.of("requestId", "req-" + i),
                    "trace-" + (i / 10)
            ));
        }

        List<LogEntry> savedLogs = logIngestService.ingestBatch(requests);
        elasticsearchIngestService.indexLogBatch(requests, savedLogs);

        // Wait for indexing
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("===========================================");
        System.out.println("FULL-TEXT SEARCH COMPARISON");
        System.out.println("===========================================");

        String[] searchTerms = {"timeout", "database", "error", "Critical database"};

        for (String term : searchTerms) {
            LogSearchRequest request = new LogSearchRequest(
                    null, null, null, null, null, term, 0, 100
            );

            // PostgreSQL search
            long pgStart = System.currentTimeMillis();
            Page<LogEntry> pgResults = postgresSearchService.search(request);
            long pgDuration = System.currentTimeMillis() - pgStart;

            // Elasticsearch search
            long esStart = System.currentTimeMillis();
            Page<LogDocument> esResults = elasticsearchSearchService.search(request);
            long esDuration = System.currentTimeMillis() - esStart;

            double speedup = (double) pgDuration / esDuration;

            System.out.println(String.format("Search: '%s'", term));
            System.out.println(String.format("  PostgreSQL:    %dms (%d results)",
                    pgDuration, pgResults.getTotalElements()));
            System.out.println(String.format("  Elasticsearch: %dms (%d results)",
                    esDuration, esResults.getTotalElements()));
            System.out.println(String.format("  Speedup: %.2fx", speedup));
            System.out.println();

            // Verify result consistency
            assertThat(pgResults.getTotalElements()).isEqualTo(esResults.getTotalElements());
        }

        System.out.println("===========================================");
    }

    @Test
    void shouldSearchThrough1000Logs() {
        // Arrange - Ingest 1000 logs first
        List<LogEntryRequest> requests = generate1000Logs();
        logIngestService.ingestBatch(requests);

        // Create various search scenarios
        LogSearchRequest[] searchRequests = {
                // Search by service
                new LogSearchRequest("auth-service", null, null, null, null, null, 0, 50),

                // Search by level
                new LogSearchRequest(null, LogStatus.ERROR, null, null, null, null, 0, 50),

                // Search by time range
                new LogSearchRequest(null, null, null,
                        Instant.now().minus(Duration.ofHours(1)),
                        Instant.now(),
                        null, 0, 50),

                // Search by text
                new LogSearchRequest(null, null, null, null, null, "timeout", 0, 50),

                // Combined search
                new LogSearchRequest("payment-service", LogStatus.INFO, null, null, null, null, 0, 50)
        };

        System.out.println("===========================================");
        System.out.println("SEARCH PERFORMANCE TEST RESULTS");
        System.out.println("===========================================");

        // Act & Assert for each search
        for (int i = 0; i < searchRequests.length; i++) {
            long startTime = System.currentTimeMillis();
            Page<LogEntry> results = postgresSearchService.search(searchRequests[i]);
            long endTime = System.currentTimeMillis();

            long duration = endTime - startTime;
            System.out.println("Search " + (i + 1) + ": Found " + results.getTotalElements() +
                    " logs in " + duration + "ms");

            // Performance assertion - search should be fast
            assertThat(duration).isLessThan(1000); // Should complete within 1 second
            assertThat(results.getContent().size()).isLessThanOrEqualTo(50); // Respects page size
        }

        System.out.println("===========================================");
    }

    @Test
    void shouldPaginateThroughAllResults() {
        // Arrange - Ingest 1000 logs
        List<LogEntryRequest> requests = generate1000Logs();
        logIngestService.ingestBatch(requests);

        // Act - Paginate through all results
        int pageSize = 50;
        int totalRetrieved = 0;
        int currentPage = 0;

        long startTime = System.currentTimeMillis();

        while (true) {
            LogSearchRequest request = new LogSearchRequest(
                    null, null, null, null, null, null, currentPage, pageSize
            );

            Page<LogEntry> results = postgresSearchService.search(request);

            totalRetrieved += results.getContent().size();

            if (!results.hasNext()) {
                break;
            }
            currentPage++;
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // Assert
        assertThat(totalRetrieved).isEqualTo(1000);
        assertThat(currentPage).isEqualTo(19); // 1000 logs / 50 per page = 20 pages (0-19)

        System.out.println("===========================================");
        System.out.println("PAGINATION TEST RESULTS");
        System.out.println("===========================================");
        System.out.println("Total logs retrieved: " + totalRetrieved);
        System.out.println("Total pages: " + (currentPage + 1));
        System.out.println("Time taken: " + duration + "ms");
        System.out.println("Average per page: " + (duration / (currentPage + 1)) + "ms");
        System.out.println("===========================================");
    }

    @Test
    void shouldHandleConcurrentSearches() throws InterruptedException {
        // Arrange - Ingest 1000 logs
        List<LogEntryRequest> requests = generate1000Logs();
        logIngestService.ingestBatch(requests);

        // Act - Execute 10 searches concurrently
        int numThreads = 10;
        Thread[] threads = new Thread[numThreads];
        long[] durations = new long[numThreads];

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                LogSearchRequest request = new LogSearchRequest(
                        null, null, null, null, null, null, 0, 100
                );

                long start = System.currentTimeMillis();
                Page<LogEntry> results = postgresSearchService.search(request);
                long end = System.currentTimeMillis();

                durations[threadId] = end - start;
                assertThat(results.getTotalElements()).isEqualTo(1000);
            });
        }

        long startTime = System.currentTimeMillis();

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        long endTime = System.currentTimeMillis();
        long totalDuration = endTime - startTime;

        // Calculate statistics
        long avgDuration = 0;
        for (long duration : durations) {
            avgDuration += duration;
        }
        avgDuration /= numThreads;

        System.out.println("===========================================");
        System.out.println("CONCURRENT SEARCH TEST RESULTS");
        System.out.println("===========================================");
        System.out.println("Number of concurrent searches: " + numThreads);
        System.out.println("Total time: " + totalDuration + "ms");
        System.out.println("Average search time: " + avgDuration + "ms");
        System.out.println("===========================================");
    }

    @Test
    void shouldMeasureElasticsearchIndexingSpeed() {
        // Arrange
        List<LogEntryRequest> requests = generate1000Logs();

        // Act - Save to PostgreSQL first
        long pgStartTime = System.currentTimeMillis();
        List<LogEntry> savedLogs = logIngestService.ingestBatch(requests);
        long pgDuration = System.currentTimeMillis() - pgStartTime;

        // Index to Elasticsearch
        long esStartTime = System.currentTimeMillis();
        elasticsearchIngestService.indexLogBatch(requests, savedLogs);
        long esDuration = System.currentTimeMillis() - esStartTime;

        System.out.println("===========================================");
        System.out.println("INDEXING PERFORMANCE COMPARISON");
        System.out.println("===========================================");
        System.out.println("PostgreSQL batch insert: " + pgDuration + "ms");
        System.out.println("Elasticsearch batch index: " + esDuration + "ms");
        System.out.println("PostgreSQL throughput: " +
                String.format("%.0f", 1000.0 / pgDuration * 1000) + " logs/sec");
        System.out.println("Elasticsearch throughput: " +
                String.format("%.0f", 1000.0 / esDuration * 1000) + " logs/sec");
        System.out.println("===========================================");

        // Verify counts
        assertThat(logRepository.count()).isEqualTo(1000);

        // Wait for Elasticsearch to finish indexing
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(elasticsearchRepository.count()).isEqualTo(1000);
    }

    private List<LogEntryRequest> generate1000Logs() {
        List<LogEntryRequest> logs = new ArrayList<>();
        Instant baseTime = Instant.now().minus(Duration.ofHours(1));

        for (int i = 0; i < 1000; i++) {
            String serviceId = services[random.nextInt(services.length)];
            LogStatus level = levels[random.nextInt(levels.length)];
            String message = messages[random.nextInt(messages.length)];
            Instant timestamp = baseTime.plus(Duration.ofSeconds(i));
            String traceId = "trace-" + (i / 10); // Groups of 10 logs share a trace

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("requestId", "req-" + i);
            metadata.put("userId", "user-" + random.nextInt(100));
            metadata.put("duration", random.nextInt(1000));

            logs.add(new LogEntryRequest(
                    timestamp,
                    serviceId,
                    level,
                    message,
                    metadata,
                    traceId
            ));
        }

        return logs;
    }
}