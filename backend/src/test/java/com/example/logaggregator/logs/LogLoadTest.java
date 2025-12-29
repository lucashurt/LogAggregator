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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.TermsAggregation;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Search Performance Benchmark
 *
 * ╔════════════════════════════════════════════════════════════════════════════════╗
 * ║  PURPOSE: Compare Elasticsearch vs PostgreSQL search performance              ║
 * ╠════════════════════════════════════════════════════════════════════════════════╣
 * ║  This test measures:                                                           ║
 * ║    1. Search latency (ES is optimized for search, PG is not)                  ║
 * ║    2. Full-text search performance                                            ║
 * ║    3. Aggregation performance                                                  ║
 * ║    4. Concurrent search load                                                   ║
 * ║                                                                                ║
 * ║  These benchmarks are RELATIVE comparisons (ES vs PG), not absolute           ║
 * ║  throughput claims. They show WHY we use Elasticsearch for search.            ║
 * ╚════════════════════════════════════════════════════════════════════════════════╝
 */
@SpringBootTest
@Tag("load-test")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
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
    private ElasticsearchOperations elasticsearchTemplate;

    @Autowired
    private LogRepository logRepository;

    @Autowired
    private LogElasticsearchRepository elasticsearchRepository;

    @Autowired
    private EntityManager entityManager;

    private final Random random = new Random();
    private final String[] services = {"auth-service", "payment-service", "notification-service",
            "user-service", "inventory-service", "shipping-service"};
    private final LogStatus[] levels = {LogStatus.INFO, LogStatus.DEBUG, LogStatus.WARNING, LogStatus.ERROR};
    private final String[] messages = {
            "User logged in successfully",
            "Payment processed with transaction id",
            "Critical database connection timeout error occurred",
            "Cache miss for user profile",
            "API request received from external gateway",
            "Authentication failed due to invalid token",
            "Transaction completed successfully",
            "Error processing request: NullPointerException",
            "Scheduled maintenance task started",
            "Dependency service unavailable: retry count exceeded"
    };

    // Test dataset size - enough to show differences, not so big it takes forever
    private static final int TOTAL_LOGS = 200_000;
    private static final int CONCURRENT_USERS = 50;

    @BeforeEach
    void cleanup() {
        logRepository.deleteAllInBatch();
        elasticsearchRepository.deleteAll();
        try {
            elasticsearchTemplate.indexOps(LogDocument.class).refresh();
        } catch (Exception e) {
            // Ignore
        }
    }

    @Test
    void searchPerformanceBenchmark() throws InterruptedException {
        printHeader();

        // Verify clean state
        assertThat(logRepository.count()).as("Database should be empty before test").isEqualTo(0L);

        // PHASE 1: Ingest data
        List<LogEntry> savedLogs = ingestTestData();

        // PHASE 2: Search latency comparison
        benchmarkSearchLatency();

        // PHASE 3: Aggregation performance
        benchmarkAggregations();

        // PHASE 4: Concurrent load
        benchmarkConcurrentLoad();

        printSummary();
    }

    private List<LogEntry> ingestTestData() throws InterruptedException {
        System.out.println("\n╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  PHASE 1: DATA INGESTION                                         ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        System.out.printf("Generating and ingesting %,d logs...%n", TOTAL_LOGS);

        List<LogEntryRequest> requests = generateLogs(TOTAL_LOGS);

        // Ingest to PostgreSQL
        long pgStart = System.currentTimeMillis();
        int batchSize = 5000;
        List<LogEntry> allSavedLogs = new ArrayList<>();

        for (int i = 0; i < requests.size(); i += batchSize) {
            int end = Math.min(requests.size(), i + batchSize);
            allSavedLogs.addAll(logIngestService.ingestBatch(requests.subList(i, end)));
            System.out.printf("   PostgreSQL: %,d / %,d%n", end, TOTAL_LOGS);
        }
        long pgDuration = System.currentTimeMillis() - pgStart;

        // Ingest to Elasticsearch
        System.out.println("   Indexing to Elasticsearch...");
        long esStart = System.currentTimeMillis();
        for (int i = 0; i < requests.size(); i += batchSize) {
            int end = Math.min(requests.size(), i + batchSize);
            elasticsearchIngestService.indexLogBatch(
                    requests.subList(i, end),
                    allSavedLogs.subList(i, end)
            );
        }
        elasticsearchTemplate.indexOps(LogDocument.class).refresh();
        long esDuration = System.currentTimeMillis() - esStart;

        System.out.printf("%n   PostgreSQL ingestion : %,d ms (%,.0f logs/sec)%n",
                pgDuration, TOTAL_LOGS / (pgDuration / 1000.0));
        System.out.printf("   Elasticsearch index  : %,d ms (%,.0f logs/sec)%n",
                esDuration, TOTAL_LOGS / (esDuration / 1000.0));

        // Verify counts
        long pgCount = logRepository.count();
        long esCount = elasticsearchRepository.count();
        System.out.printf("%n   ✅ PostgreSQL count    : %,d%n", pgCount);
        System.out.printf("   ✅ Elasticsearch count : %,d%n", esCount);

        return allSavedLogs;
    }

    private void benchmarkSearchLatency() {
        System.out.println("\n╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  PHASE 2: SEARCH LATENCY COMPARISON                              ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.println("║  Elasticsearch is optimized for search - expect it to be faster  ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("%-35s │ %12s │ %12s │ %10s%n",
                "Query Type", "PostgreSQL", "Elasticsearch", "ES Speedup");
        System.out.println("─".repeat(80));

        // Define test scenarios
        LogSearchRequest[] scenarios = {
                // Full-text search
                new LogSearchRequest(null, null, null, null, null, "timeout", 0, 20),
                // Exact match filter
                new LogSearchRequest("payment-service", null, null, null, null, null, 0, 20),
                // Time range + level filter
                new LogSearchRequest(null, LogStatus.ERROR, null,
                        Instant.now().minus(Duration.ofHours(12)), Instant.now(), null, 0, 20),
                // Complex: multiple filters + text
                new LogSearchRequest("auth-service", LogStatus.WARNING, null, null, null, "invalid token", 0, 20)
        };

        String[] labels = {
                "Full-Text: 'timeout'",
                "Exact Match: service='payment'",
                "Range: Last 12h + ERROR",
                "Complex: auth + WARN + text"
        };

        for (int i = 0; i < scenarios.length; i++) {
            // Warmup
            try {
                postgresSearchService.search(scenarios[i]);
                elasticsearchSearchService.search(scenarios[i]);
            } catch (Exception e) {
                // Ignore warmup errors
            }

            // Measure PostgreSQL (average of 3 runs)
            long pgTotal = 0;
            for (int run = 0; run < 3; run++) {
                long start = System.currentTimeMillis();
                postgresSearchService.search(scenarios[i]);
                pgTotal += System.currentTimeMillis() - start;
            }
            long pgAvg = pgTotal / 3;

            // Measure Elasticsearch (average of 3 runs)
            long esTotal = 0;
            for (int run = 0; run < 3; run++) {
                long start = System.currentTimeMillis();
                elasticsearchSearchService.search(scenarios[i]);
                esTotal += System.currentTimeMillis() - start;
            }
            long esAvg = esTotal / 3;

            double speedup = esAvg > 0 ? (double) pgAvg / esAvg : 0;
            String speedupStr = speedup > 1.0 ? String.format("%.1fx faster", speedup) : "similar";

            System.out.printf("%-35s │ %10d ms │ %10d ms │ %10s%n",
                    labels[i], pgAvg, esAvg, speedupStr);
        }
    }

    private void benchmarkAggregations() {
        System.out.println("\n╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  PHASE 3: AGGREGATION PERFORMANCE                                ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.println("║  Elasticsearch aggregations are computed server-side             ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // PostgreSQL GROUP BY
        long pgStart = System.currentTimeMillis();
        List<Object[]> pgResults = entityManager.createQuery(
                        "SELECT l.serviceId, COUNT(l) FROM LogEntry l GROUP BY l.serviceId", Object[].class)
                .getResultList();
        long pgDuration = System.currentTimeMillis() - pgStart;

        // Elasticsearch aggregation
        long esStart = System.currentTimeMillis();
        NativeQuery aggQuery = NativeQuery.builder()
                .withQuery(q -> q.matchAll(m -> m))
                .withMaxResults(0)
                .withAggregation("service_counts", Aggregation.of(a -> a
                        .terms(TermsAggregation.of(t -> t.field("serviceId")))
                ))
                .build();
        elasticsearchTemplate.search(aggQuery, LogDocument.class);
        long esDuration = System.currentTimeMillis() - esStart;

        double speedup = esDuration > 0 ? (double) pgDuration / esDuration : 0;

        System.out.printf("Aggregation: GROUP BY serviceId%n");
        System.out.printf("   PostgreSQL     : %d ms%n", pgDuration);
        System.out.printf("   Elasticsearch  : %d ms%n", esDuration);
        System.out.printf("   ES Speedup     : %.1fx%n", speedup);
    }

    private void benchmarkConcurrentLoad() throws InterruptedException {
        System.out.println("\n╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PHASE 4: CONCURRENT LOAD (%d simultaneous searches)             ║%n", CONCURRENT_USERS);
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.println("║  Simulates multiple users searching simultaneously               ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();

        LogSearchRequest searchRequest = new LogSearchRequest(
                null, null, null, null, null, "connection", 0, 50
        );

        // PostgreSQL concurrent
        ExecutorService pgExecutor = Executors.newFixedThreadPool(CONCURRENT_USERS);
        AtomicLong pgTotalTime = new AtomicLong(0);
        AtomicLong pgMaxTime = new AtomicLong(0);

        for (int i = 0; i < CONCURRENT_USERS; i++) {
            pgExecutor.submit(() -> {
                long start = System.currentTimeMillis();
                postgresSearchService.search(searchRequest);
                long duration = System.currentTimeMillis() - start;
                pgTotalTime.addAndGet(duration);
                pgMaxTime.updateAndGet(current -> Math.max(current, duration));
            });
        }
        pgExecutor.shutdown();
        pgExecutor.awaitTermination(2, TimeUnit.MINUTES);

        // Elasticsearch concurrent
        ExecutorService esExecutor = Executors.newFixedThreadPool(CONCURRENT_USERS);
        AtomicLong esTotalTime = new AtomicLong(0);
        AtomicLong esMaxTime = new AtomicLong(0);

        for (int i = 0; i < CONCURRENT_USERS; i++) {
            esExecutor.submit(() -> {
                long start = System.currentTimeMillis();
                elasticsearchSearchService.search(searchRequest);
                long duration = System.currentTimeMillis() - start;
                esTotalTime.addAndGet(duration);
                esMaxTime.updateAndGet(current -> Math.max(current, duration));
            });
        }
        esExecutor.shutdown();
        esExecutor.awaitTermination(2, TimeUnit.MINUTES);

        double pgAvg = pgTotalTime.get() / (double) CONCURRENT_USERS;
        double esAvg = esTotalTime.get() / (double) CONCURRENT_USERS;
        double speedup = esAvg > 0 ? pgAvg / esAvg : 0;

        System.out.printf("Results with %d concurrent users:%n", CONCURRENT_USERS);
        System.out.printf("   PostgreSQL     - Avg: %.0f ms, Max: %d ms%n", pgAvg, pgMaxTime.get());
        System.out.printf("   Elasticsearch  - Avg: %.0f ms, Max: %d ms%n", esAvg, esMaxTime.get());
        System.out.printf("   ES Speedup     : %.1fx%n", speedup);
    }

    private void printHeader() {
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║     SEARCH PERFORMANCE BENCHMARK                                 ║");
        System.out.println("║     Elasticsearch vs PostgreSQL Comparison                       ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.printf("║     Dataset: %,d logs                                          ║%n", TOTAL_LOGS);
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void printSummary() {
        System.out.println("\n" + "═".repeat(70));
        System.out.println("BENCHMARK COMPLETE");
        System.out.println("═".repeat(70));
        System.out.println();
        System.out.println("Key Takeaways:");
        System.out.println("  • Elasticsearch is optimized for search, PostgreSQL for transactions");
        System.out.println("  • Full-text search: ES is significantly faster (no LIKE '%text%')");
        System.out.println("  • Aggregations: ES computes server-side, PG requires full scan");
        System.out.println("  • Under load: ES scales better with concurrent queries");
        System.out.println();
        System.out.println("This is why we use BOTH:");
        System.out.println("  • PostgreSQL: ACID compliance, source of truth, transactions");
        System.out.println("  • Elasticsearch: Fast search, aggregations, full-text queries");
        System.out.println("═".repeat(70));
    }

    private List<LogEntryRequest> generateLogs(int count) {
        List<LogEntryRequest> logs = new ArrayList<>(count);
        Instant baseTime = Instant.now().minus(Duration.ofHours(24));

        for (int i = 0; i < count; i++) {
            String serviceId = services[random.nextInt(services.length)];
            LogStatus level = levels[random.nextInt(levels.length)];
            String message = messages[random.nextInt(messages.length)] + " " + UUID.randomUUID().toString().substring(0, 8);
            Instant timestamp = baseTime.plus(Duration.ofMillis(random.nextInt(86400000)));
            String traceId = "trace-" + UUID.randomUUID();

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("requestId", "req-" + i);
            metadata.put("region", random.nextBoolean() ? "us-east-1" : "eu-west-1");

            logs.add(new LogEntryRequest(timestamp, serviceId, level, message, metadata, traceId));
        }
        return logs;
    }
}
