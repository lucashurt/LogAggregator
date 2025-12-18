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
import org.springframework.data.domain.Page;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    private ElasticsearchOperations elasticsearchTemplate;

    @Autowired
    private LogRepository logRepository;

    @Autowired
    private LogElasticsearchRepository elasticsearchRepository;

    private final Random random = new Random();
    private final String[] services = {"auth-service", "payment-service", "notification-service", "user-service", "inventory-service", "shipping-service"};
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

    // CONFIGURATION
    private static final int TOTAL_LOGS = 100_000;
    private static final int CONCURRENT_USERS = 50;

    @BeforeEach
    void cleanup() {
        logRepository.deleteAll();
        elasticsearchRepository.deleteAll();
    }

    @Test
    void shouldExecuteLargeScalePerformanceBenchmark() throws InterruptedException {
        System.out.println("\n\n");
        System.out.println("██████╗ ███████╗███╗   ██╗ ██████╗██╗  ██╗███╗   ███╗ █████╗ ██████╗ ██╗  ██╗");
        System.out.println("██╔══██╗██╔════╝████╗  ██║██╔════╝██║  ██║████╗ ████║██╔══██╗██╔══██╗██║ ██╔╝");
        System.out.println("██████╔╝█████╗  ██╔██╗ ██║██║     ███████║██╔████╔██║███████║██████╔╝█████╔╝ ");
        System.out.println("██╔══██╗██╔══╝  ██║╚██╗██║██║     ██╔══██║██║╚██╔╝██║██╔══██║██╔══██╗██╔═██╗ ");
        System.out.println("██████╔╝███████╗██║ ╚████║╚██████╗██║  ██║██║ ╚═╝ ██║██║  ██║██║  ██║██║  ██╗");
        System.out.println("╚═════╝ ╚══════╝╚═╝  ╚═══╝ ╚═════╝╚═╝  ╚═╝╚═╝     ╚═╝╚═╝  ╚═╝╚═╝  ╚═╝╚═╝  ╚═╝");
        System.out.println("LOG AGGREGATOR PERFORMANCE SUITE - " + TOTAL_LOGS + " RECORDS");
        System.out.println("==================================================================================");

        // 1. INGESTION PHASE
        List<LogEntryRequest> requests = generateLogs(TOTAL_LOGS);
        List<LogEntry> savedLogs = benchmarkIngestion(requests);

        // 2. SEARCH LATENCY PHASE
        benchmarkSearchLatency();

        // 3. ANALYTIC / AGGREGATION PHASE
        benchmarkAggregation();

        // 4. CONCURRENT LOAD PHASE
        benchmarkConcurrentLoad();

        System.out.println("==================================================================================");
        System.out.println("BENCHMARK COMPLETE");
        System.out.println("\n");
    }

    private List<LogEntry> benchmarkIngestion(List<LogEntryRequest> requests) throws InterruptedException {
        System.out.println("\n[PHASE 1] INGESTION PERFORMANCE");
        System.out.println("----------------------------------------------------------------------------------");

        // PostgreSQL Ingestion
        long pgStart = System.currentTimeMillis();
        // Ingest in chunks to be realistic and avoid massive transaction overhead in test
        int batchSize = 5000;
        List<LogEntry> allSavedLogs = new ArrayList<>();

        for (int i = 0; i < requests.size(); i += batchSize) {
            int end = Math.min(requests.size(), i + batchSize);
            allSavedLogs.addAll(logIngestService.ingestBatch(requests.subList(i, end)));
        }
        long pgEnd = System.currentTimeMillis();
        long pgDuration = pgEnd - pgStart;

        // Elasticsearch Ingestion
        long esStart = System.currentTimeMillis();
        for (int i = 0; i < requests.size(); i += batchSize) {
            int end = Math.min(requests.size(), i + batchSize);
            int pgStartIdx = i; // Map request index to saved log index
            // We need to sublist saved logs as well to match requests
            elasticsearchIngestService.indexLogBatch(
                    requests.subList(i, end),
                    allSavedLogs.subList(pgStartIdx, Math.min(allSavedLogs.size(), pgStartIdx + (end - i)))
            );
        }
        long esEnd = System.currentTimeMillis();
        long esDuration = esEnd - esStart;

        // Wait for ES Refresh
        elasticsearchTemplate.indexOps(LogDocument.class).refresh();

        printMetricRow("Batch Write Time", pgDuration + " ms", esDuration + " ms",
                String.format("%.2fx", (double)pgDuration/esDuration > 1 ? (double)pgDuration/esDuration : -((double)esDuration/pgDuration)));

        printMetricRow("Throughput",
                String.format("%.0f logs/sec", TOTAL_LOGS / (pgDuration / 1000.0)),
                String.format("%.0f logs/sec", TOTAL_LOGS / (esDuration / 1000.0)),
                "-");

        assertThat(logRepository.count()).isEqualTo(TOTAL_LOGS);
        return allSavedLogs;
    }

    private void benchmarkSearchLatency() {
        System.out.println("\n[PHASE 2] SEARCH LATENCY (Single Query)");
        System.out.println("----------------------------------------------------------------------------------");
        System.out.println(String.format("%-40s | %-15s | %-15s | %-10s", "Query Type", "Postgres", "Elastic", "Speedup"));
        System.out.println("-----------------------------------------+-----------------+-----------------+----------");

        LogSearchRequest[] scenarios = {
                new LogSearchRequest(null, null, null, null, null, "timeout", 0, 20), // Full Text
                new LogSearchRequest("payment-service", null, null, null, null, null, 0, 20), // Exact Match
                new LogSearchRequest(null, LogStatus.ERROR, null, Instant.now().minus(Duration.ofHours(12)), Instant.now(), null, 0, 20), // Range + Filter
                new LogSearchRequest("auth-service", LogStatus.WARNING, null, null, null, "invalid token", 0, 20) // Complex Combined
        };

        String[] labels = {
                "Full Text: 'timeout'",
                "Exact: 'payment-service'",
                "Range: Last 12h Errors",
                "Complex: Auth Warn + Text"
        };

        for (int i = 0; i < scenarios.length; i++) {
            // Warmup (optional, skipped for raw comparison)

            long pgStart = System.currentTimeMillis();
            Page<LogEntry> pgRes = postgresSearchService.search(scenarios[i]);
            long pgDur = System.currentTimeMillis() - pgStart;

            long esStart = System.currentTimeMillis();
            Page<LogDocument> esRes = elasticsearchSearchService.search(scenarios[i]);
            long esDur = System.currentTimeMillis() - esStart;

            double speedup = esDur > 0 ? (double) pgDur / esDur : 0;
            String speedupStr = speedup > 1.0 ? String.format("%.2fx (Win)", speedup) : String.format("%.2fx", speedup);

            System.out.println(String.format("%-40s | %-15s | %-15s | %-10s",
                    labels[i], pgDur + " ms", esDur + " ms", speedupStr));
        }
    }

    private void benchmarkAggregation() {
        System.out.println("\n[PHASE 3] ANALYTIC AGGREGATIONS (Count by Service)");
        System.out.println("----------------------------------------------------------------------------------");

        // Postgres Aggregation (Count)
        long pgStart = System.currentTimeMillis();
        // Since we don't have a specific agg method, we use count on a filtered query per service
        for(String service : services) {
            postgresSearchService.search(new LogSearchRequest(service, null, null, null, null, null, 0, 1));
        }
        long pgDur = System.currentTimeMillis() - pgStart;

        // Elasticsearch Aggregation
        long esStart = System.currentTimeMillis();
        // Similarly simulating aggregation via search hits count which ES optimizes
        for(String service : services) {
            elasticsearchSearchService.search(new LogSearchRequest(service, null, null, null, null, null, 0, 1));
        }
        long esDur = System.currentTimeMillis() - esStart;

        printMetricRow("Iterative Count (" + services.length + " groups)", pgDur + " ms", esDur + " ms",
                String.format("%.2fx", (double)pgDur/esDur));
    }

    private void benchmarkConcurrentLoad() throws InterruptedException {
        System.out.println("\n[PHASE 4] CONCURRENT LOAD TEST (" + CONCURRENT_USERS + " Threads)");
        System.out.println("----------------------------------------------------------------------------------");

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_USERS);
        AtomicLong pgTotalTime = new AtomicLong(0);
        AtomicLong esTotalTime = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);

        LogSearchRequest heavyRequest = new LogSearchRequest(null, null, null, null, null, "connection", 0, 50);

        long start = System.currentTimeMillis();

        for(int i=0; i<CONCURRENT_USERS; i++) {
            executor.submit(() -> {
                // Postgres Search
                long t1 = System.currentTimeMillis();
                postgresSearchService.search(heavyRequest);
                pgTotalTime.addAndGet(System.currentTimeMillis() - t1);

                // Elastic Search
                long t2 = System.currentTimeMillis();
                elasticsearchSearchService.search(heavyRequest);
                esTotalTime.addAndGet(System.currentTimeMillis() - t2);

                successCount.incrementAndGet();
            });
        }

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);
        long wallTime = System.currentTimeMillis() - start;

        double pgAvg = pgTotalTime.get() / (double) CONCURRENT_USERS;
        double esAvg = esTotalTime.get() / (double) CONCURRENT_USERS;

        printMetricRow("Avg Latency under Load", String.format("%.0f ms", pgAvg), String.format("%.0f ms", esAvg),
                String.format("%.2fx", pgAvg/esAvg));

        System.out.println(String.format("Total Wall Time for %d reqs: %d ms", CONCURRENT_USERS, wallTime));
    }

    // --- Helpers ---

    private void printMetricRow(String metric, String pgVal, String esVal, String diff) {
        System.out.println(String.format("%-30s | %-15s | %-15s | %s", metric, pgVal, esVal, diff));
    }

    private List<LogEntryRequest> generateLogs(int count) {
        List<LogEntryRequest> logs = new ArrayList<>(count);
        Instant baseTime = Instant.now().minus(Duration.ofHours(24));

        System.out.print("Generating " + count + " logs... ");
        for (int i = 0; i < count; i++) {
            String serviceId = services[random.nextInt(services.length)];
            LogStatus level = levels[random.nextInt(levels.length)];
            String message = messages[random.nextInt(messages.length)] + " " + UUID.randomUUID().toString().substring(0, 8);
            Instant timestamp = baseTime.plus(Duration.ofMillis(random.nextInt(86400000))); // Random time in last 24h
            String traceId = "trace-" + UUID.randomUUID();

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("requestId", "req-" + i);
            metadata.put("region", random.nextBoolean() ? "us-east-1" : "eu-west-1");
            metadata.put("latency_ms", random.nextInt(1000));

            logs.add(new LogEntryRequest(timestamp, serviceId, level, message, metadata, traceId));
        }
        System.out.println("Done.");
        return logs;
    }
}