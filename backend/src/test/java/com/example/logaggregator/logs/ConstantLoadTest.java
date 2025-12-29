package com.example.logaggregator.logs;

import com.example.logaggregator.BaseIntegrationTest;
import com.example.logaggregator.elasticsearch.LogDocument;
import com.example.logaggregator.elasticsearch.LogElasticsearchRepository;
import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import com.example.logaggregator.logs.models.LogStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-End Throughput Test
 *
 * ╔════════════════════════════════════════════════════════════════════════════════╗
 * ║  IMPORTANT: UNDERSTANDING THESE BENCHMARKS                                     ║
 * ╠════════════════════════════════════════════════════════════════════════════════╣
 * ║  These tests run in Testcontainers - an ISOLATED environment that differs     ║
 * ║  significantly from your Docker Compose production setup:                      ║
 * ║                                                                                ║
 * ║  TESTCONTAINERS (these tests):                                                 ║
 * ║    ✓ No frontend (no WebSocket broadcasting overhead)                         ║
 * ║    ✓ No browser rendering thousands of logs                                   ║
 * ║    ✓ Optimized container resource allocation                                  ║
 * ║    ✓ No Python script overhead                                                ║
 * ║                                                                                ║
 * ║  DOCKER COMPOSE + stream_logs.py (real usage):                                ║
 * ║    ✗ Full stack running (7+ containers)                                       ║
 * ║    ✗ WebSocket broadcasting every log batch                                   ║
 * ║    ✗ Browser rendering logs in real-time                                      ║
 * ║    ✗ Python HTTP client overhead                                              ║
 * ║    ✗ All services competing for CPU/memory                                    ║
 * ║                                                                                ║
 * ║  EXPECT: Real-world throughput to be 30-50% of test results                   ║
 * ╚════════════════════════════════════════════════════════════════════════════════╝
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("load-test")
@ActiveProfiles("test")
public class ConstantLoadTest extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private LogRepository logRepository;

    @Autowired
    private LogElasticsearchRepository elasticsearchRepository;

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    // ==================== REALISTIC CONFIGURATION ====================
    // These targets are achievable on typical developer hardware (8-16GB RAM, 4-8 cores)

    /** Target for sustained test - achievable on most machines */
    private static final int TARGET_LOGS_PER_SECOND = 5000;

    /** Test duration - long enough to see trends */
    private static final int TEST_DURATION_SECONDS = 90;

    /** Batch size for API calls */
    private static final int BATCH_SIZE = 1500;

    /** How often to report progress */
    private static final int REPORT_INTERVAL_SECONDS = 5;

    /** Thread pool for HTTP requests */
    private static final int HTTP_THREAD_POOL_SIZE = 4;

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

    @BeforeEach
    void cleanup() {
        logRepository.deleteAll();
        elasticsearchRepository.deleteAll();
        try {
            elasticsearchOperations.indexOps(LogDocument.class).refresh();
        } catch (Exception e) {
            // Ignore
        }
    }

    private long getElasticsearchCount() {
        try {
            elasticsearchOperations.indexOps(LogDocument.class).refresh();
            return elasticsearchRepository.count();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * PRIMARY TEST: Measures end-to-end throughput at a sustainable rate.
     *
     * This test answers: "How many logs per second can the full pipeline handle?"
     * Pipeline: API → Kafka → PostgreSQL + Elasticsearch
     */
    @Test
    void measureEndToEndThroughput() throws InterruptedException {
        printHeader();

        AtomicLong totalLogsSent = new AtomicLong(0);
        AtomicLong totalLogsAccepted = new AtomicLong(0);
        List<Long> apiLatencies = new CopyOnWriteArrayList<>();
        List<IntervalMetrics> intervalHistory = new CopyOnWriteArrayList<>();

        // Calculate timing
        int batchesPerSecond = Math.max(1, TARGET_LOGS_PER_SECOND / BATCH_SIZE);
        long delayBetweenBatches = 1000 / batchesPerSecond;

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  TEST CONFIGURATION                                              ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Target Rate      : %,d logs/sec                                 ║%n", TARGET_LOGS_PER_SECOND);
        System.out.printf("║  Test Duration    : %d seconds                                   ║%n", TEST_DURATION_SECONDS);
        System.out.printf("║  Batch Size       : %d logs/request                              ║%n", BATCH_SIZE);
        System.out.printf("║  Expected Total   : %,d logs                                   ║%n", TARGET_LOGS_PER_SECOND * TEST_DURATION_SECONDS);
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();

        ExecutorService httpExecutor = Executors.newFixedThreadPool(HTTP_THREAD_POOL_SIZE);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        long testStartTime = System.currentTimeMillis();
        AtomicLong lastReportTime = new AtomicLong(testStartTime);
        AtomicLong lastReportSent = new AtomicLong(0);

        // Send batches at regular intervals
        ScheduledFuture<?> sendTask = scheduler.scheduleAtFixedRate(() -> {
            httpExecutor.submit(() -> {
                try {
                    List<LogEntryRequest> batch = generateBatch(BATCH_SIZE);
                    long requestStart = System.nanoTime();
                    ResponseEntity<String> response = sendBatch(batch);
                    long latencyMs = (System.nanoTime() - requestStart) / 1_000_000;

                    totalLogsSent.addAndGet(BATCH_SIZE);
                    if (response.getStatusCode().is2xxSuccessful()) {
                        totalLogsAccepted.addAndGet(BATCH_SIZE);
                        apiLatencies.add(latencyMs);
                    }
                } catch (Exception e) {
                    // Continue on error
                }
            });
        }, 0, delayBetweenBatches, TimeUnit.MILLISECONDS);

        // Progress reporting
        ScheduledFuture<?> reportTask = scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            long elapsed = (now - testStartTime) / 1000;
            long intervalMs = now - lastReportTime.get();
            long intervalSent = totalLogsAccepted.get() - lastReportSent.get();
            lastReportTime.set(now);
            lastReportSent.set(totalLogsAccepted.get());

            double apiRate = intervalMs > 0 ? (intervalSent * 1000.0 / intervalMs) : 0;
            long pgCount = logRepository.count();
            long esCount = getElasticsearchCount();

            IntervalMetrics metrics = new IntervalMetrics(
                    elapsed, totalLogsAccepted.get(), apiRate, pgCount, esCount
            );
            intervalHistory.add(metrics);

            String status = (pgCount >= totalLogsAccepted.get() * 0.9) ? "✅" : "⏳";
            System.out.printf("%s [%3ds] API: %,.0f/s | Accepted: %,d | PG: %,d | ES: %,d%n",
                    status, elapsed, apiRate, totalLogsAccepted.get(), pgCount, esCount);

        }, REPORT_INTERVAL_SECONDS, REPORT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // Run test
        Thread.sleep(TEST_DURATION_SECONDS * 1000L);

        // Shutdown
        sendTask.cancel(false);
        reportTask.cancel(false);
        scheduler.shutdown();
        httpExecutor.shutdown();
        scheduler.awaitTermination(5, TimeUnit.SECONDS);
        httpExecutor.awaitTermination(10, TimeUnit.SECONDS);

        // Wait for pipeline to drain
        System.out.println("\n⏳ Waiting for pipeline to fully process all logs...");
        long drainStart = System.currentTimeMillis();
        long maxDrainMs = 30000;

        while (System.currentTimeMillis() - drainStart < maxDrainMs) {
            long pgCount = logRepository.count();
            long esCount = getElasticsearchCount();

            if (pgCount >= totalLogsAccepted.get() * 0.99 && esCount >= totalLogsAccepted.get() * 0.95) {
                System.out.printf("   ✅ Pipeline drained: PG=%,d | ES=%,d%n", pgCount, esCount);
                break;
            }
            Thread.sleep(500);
        }

        // Final results
        long totalDuration = System.currentTimeMillis() - testStartTime;
        long finalPgCount = logRepository.count();
        long finalEsCount = getElasticsearchCount();

        printResults(totalLogsAccepted.get(), finalPgCount, finalEsCount, totalDuration, apiLatencies, intervalHistory);

        // Assertions
        assertThat(finalPgCount).isGreaterThan((long)(totalLogsAccepted.get() * 0.95));
    }

    /**
     * CAPACITY TEST: Find the maximum rate before the pipeline falls behind.
     */
    @Test
    void findMaxSustainableRate() throws InterruptedException {
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  CAPACITY DISCOVERY TEST                                         ║");
        System.out.println("║  Finding maximum sustainable throughput (Testcontainers)         ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Test progressively higher rates
        int[] testRates = {100, 250, 500, 1000, 2000, 3000, 5000};
        int testDurationPerRate = 10; // seconds

        List<RateTestResult> results = new ArrayList<>();
        int maxSustainable = 0;

        for (int rate : testRates) {
            // Clean up
            logRepository.deleteAll();
            elasticsearchRepository.deleteAll();
            Thread.sleep(2000);

            System.out.printf("Testing %,d logs/sec for %d seconds...%n", rate, testDurationPerRate);

            RateTestResult result = testSpecificRate(rate, testDurationPerRate);
            results.add(result);

            System.out.printf("   → Actual: %,.0f/s | Processed: %.1f%% | %s%n",
                    result.actualThroughput,
                    result.processingEfficiency,
                    result.sustainable ? "✅ SUSTAINABLE" : "❌ FALLING BEHIND");

            if (result.sustainable) {
                maxSustainable = rate;
            } else {
                System.out.println("   Stopping - system cannot sustain this rate.");
                break;
            }
        }

        // Summary
        System.out.println("\n" + "═".repeat(70));
        System.out.println("CAPACITY TEST RESULTS (Testcontainers Environment)");
        System.out.println("═".repeat(70));
        System.out.printf("%-10s | %-12s | %-12s | %-10s%n", "Target", "Actual", "Efficiency", "Status");
        System.out.println("-".repeat(70));

        for (RateTestResult r : results) {
            System.out.printf("%-,10d | %-,12.0f | %10.1f%% | %-10s%n",
                    r.targetRate, r.actualThroughput, r.processingEfficiency,
                    r.sustainable ? "✅" : "❌");
        }

        System.out.println("═".repeat(70));
        System.out.printf("🏆 Max Testcontainer Rate : %,d logs/sec%n", maxSustainable);
        System.out.printf("📊 Estimated Real-World   : %,d - %,d logs/sec%n",
                (int)(maxSustainable * 0.3), (int)(maxSustainable * 0.5));
        System.out.printf("💡 Recommended Production : %,d logs/sec (conservative)%n",
                (int)(maxSustainable * 0.25));
        System.out.println("═".repeat(70));
    }

    // ==================== HELPER METHODS ====================

    private RateTestResult testSpecificRate(int targetRate, int durationSeconds) throws InterruptedException {
        AtomicLong totalAccepted = new AtomicLong(0);

        int batchSize = Math.min(250, Math.max(50, targetRate / 10));
        int batchesPerSecond = Math.max(1, targetRate / batchSize);
        long delayMs = Math.max(1, 1000 / batchesPerSecond);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            executor.submit(() -> {
                try {
                    List<LogEntryRequest> batch = generateBatch(batchSize);
                    ResponseEntity<String> response = sendBatch(batch);
                    if (response.getStatusCode().is2xxSuccessful()) {
                        totalAccepted.addAndGet(batchSize);
                    }
                } catch (Exception ignored) {}
            });
        }, 0, delayMs, TimeUnit.MILLISECONDS);

        Thread.sleep(durationSeconds * 1000L);

        task.cancel(false);
        scheduler.shutdown();
        executor.shutdown();
        scheduler.awaitTermination(2, TimeUnit.SECONDS);
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Wait for processing
        Thread.sleep(3000);

        long pgCount = logRepository.count();
        double actualThroughput = pgCount / (double) durationSeconds;
        double efficiency = totalAccepted.get() > 0 ? (pgCount * 100.0 / totalAccepted.get()) : 0;

        // Sustainable if we processed at least 90% of accepted logs
        boolean sustainable = efficiency >= 90.0;

        return new RateTestResult(targetRate, actualThroughput, efficiency, sustainable);
    }

    private List<LogEntryRequest> generateBatch(int size) {
        List<LogEntryRequest> batch = new ArrayList<>(size);
        Instant now = Instant.now();

        for (int i = 0; i < size; i++) {
            batch.add(new LogEntryRequest(
                    now.minusMillis(random.nextInt(1000)),
                    services[random.nextInt(services.length)],
                    levels[random.nextInt(levels.length)],
                    messages[random.nextInt(messages.length)] + " - " + UUID.randomUUID().toString().substring(0, 8),
                    Map.of(
                            "requestId", "req-" + random.nextInt(10000),
                            "region", random.nextBoolean() ? "us-east-1" : "eu-west-1"
                    ),
                    "trace-" + UUID.randomUUID()
            ));
        }
        return batch;
    }

    private ResponseEntity<String> sendBatch(List<LogEntryRequest> batch) {
        String url = "http://localhost:" + port + "/api/v1/logs/batch";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<List<LogEntryRequest>> request = new HttpEntity<>(batch, headers);
        return restTemplate.postForEntity(url, request, String.class);
    }

    private void printHeader() {
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║     END-TO-END THROUGHPUT TEST (Testcontainers)                  ║");
        System.out.println("║     API → Kafka → PostgreSQL + Elasticsearch                     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void printResults(long totalAccepted, long pgCount, long esCount, long duration,
                              List<Long> latencies, List<IntervalMetrics> history) {

        double pgThroughput = pgCount / (duration / 1000.0);
        double esEfficiency = totalAccepted > 0 ? ((double) esCount / totalAccepted) * 100 : 0;

        System.out.println("\n" + "═".repeat(70));
        System.out.println("TEST RESULTS");
        System.out.println("═".repeat(70));
        System.out.printf("Logs Accepted by API    : %,d%n", totalAccepted);
        System.out.printf("PostgreSQL Final Count  : %,d (%.1f%%)%n", pgCount,
                totalAccepted > 0 ? (pgCount * 100.0 / totalAccepted) : 0);
        System.out.printf("Elasticsearch Count     : %,d (%.1f%%)%n", esCount, esEfficiency);
        System.out.printf("Test Duration           : %.1f seconds%n", duration / 1000.0);
        System.out.printf("Effective Throughput    : %,.0f logs/sec%n", pgThroughput);

        if (!latencies.isEmpty()) {
            Collections.sort(latencies);
            System.out.println("\nAPI Response Latency:");
            System.out.printf("   P50: %dms | P95: %dms | P99: %dms%n",
                    latencies.get(latencies.size() / 2),
                    latencies.get((int)(latencies.size() * 0.95)),
                    latencies.get((int)(latencies.size() * 0.99)));
        }

        System.out.println("\n" + "═".repeat(70));
        System.out.println("⚠️  IMPORTANT: REAL-WORLD EXPECTATIONS");
        System.out.println("═".repeat(70));
        System.out.printf("These results: %,.0f logs/sec (Testcontainers, no frontend)%n", pgThroughput);
        System.out.printf("With Docker Compose + frontend + stream_logs.py:%n");
        System.out.printf("   Expected: %,.0f - %,.0f logs/sec%n", pgThroughput * 0.3, pgThroughput * 0.5);
        System.out.printf("   Recommended target: %,.0f logs/sec%n", pgThroughput * 0.25);
        System.out.println("═".repeat(70));
    }

    // ==================== INNER CLASSES ====================

    private record IntervalMetrics(
            long elapsedSeconds,
            long totalAccepted,
            double apiRate,
            long pgCount,
            long esCount
    ) {}

    private record RateTestResult(
            int targetRate,
            double actualThroughput,
            double processingEfficiency,
            boolean sustainable
    ) {}
}