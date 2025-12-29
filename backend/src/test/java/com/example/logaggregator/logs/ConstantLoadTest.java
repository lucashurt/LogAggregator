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
 * COMPLETE End-to-End Load Test
 *
 * Measures the FULL pipeline including:
 * - HTTP API acceptance
 * - Kafka producer/consumer
 * - PostgreSQL persistence
 * - Elasticsearch indexing
 *
 * This gives realistic production throughput numbers.
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

    // ==================== CONFIGURATION ====================
    private static final int TARGET_LOGS_PER_SECOND = 500;
    private static final int TEST_DURATION_SECONDS = 30;
    private static final int BATCH_SIZE = 500;
    private static final int MEASUREMENT_INTERVAL_SECONDS = 5;
    private static final int HTTP_THREAD_POOL_SIZE = 8;

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

        // Refresh ES index to ensure clean state
        try {
            elasticsearchOperations.indexOps(LogDocument.class).refresh();
        } catch (Exception e) {
            // Ignore
        }
    }

    /**
     * Helper to get Elasticsearch count with refresh
     */
    private long getElasticsearchCount() {
        try {
            elasticsearchOperations.indexOps(LogDocument.class).refresh();
            return elasticsearchRepository.count();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * TEST 1: Measure sustained throughput at a target rate.
     * Measures BOTH PostgreSQL and Elasticsearch.
     */
    @Test
    void measureSustainedThroughput() throws InterruptedException {
        printHeader();

        AtomicLong totalLogsSent = new AtomicLong(0);
        AtomicLong totalLogsAccepted = new AtomicLong(0);
        List<Long> latencies = new CopyOnWriteArrayList<>();
        List<IntervalMetrics> intervalMetrics = new CopyOnWriteArrayList<>();

        int batchesPerSecond = (int) Math.ceil((double) TARGET_LOGS_PER_SECOND / BATCH_SIZE);
        batchesPerSecond = Math.max(1, batchesPerSecond);
        long delayBetweenBatches = 1000 / batchesPerSecond;

        System.out.printf("📊 Configuration:%n");
        System.out.printf("   Target Rate     : %,d logs/sec%n", TARGET_LOGS_PER_SECOND);
        System.out.printf("   Batch Size      : %d logs%n", BATCH_SIZE);
        System.out.printf("   Batches/Second  : %d%n", batchesPerSecond);
        System.out.printf("   HTTP Threads    : %d%n", HTTP_THREAD_POOL_SIZE);
        System.out.printf("   Test Duration   : %d seconds%n", TEST_DURATION_SECONDS);
        System.out.printf("   Expected Total  : %,d logs%n", TARGET_LOGS_PER_SECOND * TEST_DURATION_SECONDS);
        System.out.printf("   Tracking        : PostgreSQL + Elasticsearch%n");
        System.out.println("-".repeat(80));

        ExecutorService httpExecutor = Executors.newFixedThreadPool(HTTP_THREAD_POOL_SIZE);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        AtomicLong intervalStart = new AtomicLong(System.currentTimeMillis());
        AtomicLong intervalLogsSent = new AtomicLong(0);

        long testStartTime = System.currentTimeMillis();

        ScheduledFuture<?> sendTask = scheduler.scheduleAtFixedRate(() -> {
            httpExecutor.submit(() -> {
                try {
                    List<LogEntryRequest> batch = generateBatch(BATCH_SIZE);

                    long requestStart = System.nanoTime();
                    ResponseEntity<String> response = sendBatch(batch);
                    long requestDuration = System.nanoTime() - requestStart;

                    totalLogsSent.addAndGet(BATCH_SIZE);

                    if (response.getStatusCode().is2xxSuccessful()) {
                        totalLogsAccepted.addAndGet(BATCH_SIZE);
                        intervalLogsSent.addAndGet(BATCH_SIZE);
                        latencies.add(requestDuration / 1_000_000);
                    }
                } catch (Exception e) {
                    System.err.println("❌ Batch send failed: " + e.getMessage());
                }
            });
        }, 0, delayBetweenBatches, TimeUnit.MILLISECONDS);

        // Progress reporting - now includes BOTH stores
        ScheduledFuture<?> reportTask = scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            long intervalDuration = now - intervalStart.get();
            long logsInInterval = intervalLogsSent.getAndSet(0);
            intervalStart.set(now);

            double intervalThroughput = intervalDuration > 0 ?
                    (logsInInterval / (intervalDuration / 1000.0)) : 0;

            long pgCount = logRepository.count();
            long esCount = getElasticsearchCount();
            long pgLag = totalLogsAccepted.get() - pgCount;
            long esLag = totalLogsAccepted.get() - esCount;

            IntervalMetrics metrics = new IntervalMetrics(
                    (now - testStartTime) / 1000,
                    logsInInterval,
                    intervalThroughput,
                    pgCount,
                    esCount,
                    pgLag,
                    esLag
            );
            intervalMetrics.add(metrics);

            System.out.printf("⏱️  [%3ds] API: %,.0f/s | PG: %,d (lag:%,d) | ES: %,d (lag:%,d)%n",
                    metrics.elapsedSeconds, metrics.throughput,
                    metrics.pgCount, metrics.pgLag,
                    metrics.esCount, metrics.esLag);

        }, MEASUREMENT_INTERVAL_SECONDS, MEASUREMENT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        Thread.sleep(TEST_DURATION_SECONDS * 1000L);

        sendTask.cancel(false);
        reportTask.cancel(false);
        scheduler.shutdown();
        httpExecutor.shutdown();
        scheduler.awaitTermination(5, TimeUnit.SECONDS);
        httpExecutor.awaitTermination(10, TimeUnit.SECONDS);

        // Wait for BOTH stores to catch up
        System.out.println("\n⏳ Waiting for both PostgreSQL and Elasticsearch to process...");
        long waitStart = System.currentTimeMillis();
        long maxWaitMs = Math.max(30000, totalLogsAccepted.get() / 500 * 100);

        while (System.currentTimeMillis() - waitStart < maxWaitMs) {
            long pgCount = logRepository.count();
            long esCount = getElasticsearchCount();

            double pgProgress = totalLogsAccepted.get() > 0 ?
                    (pgCount * 100.0 / totalLogsAccepted.get()) : 0;
            double esProgress = totalLogsAccepted.get() > 0 ?
                    (esCount * 100.0 / totalLogsAccepted.get()) : 0;

            // Both need to be at 99%+
            if (pgCount >= totalLogsAccepted.get() * 0.99 &&
                    esCount >= totalLogsAccepted.get() * 0.99) {
                System.out.printf("   ✅ Pipeline drained: PG=%,d (%.1f%%) | ES=%,d (%.1f%%)%n",
                        pgCount, pgProgress, esCount, esProgress);
                break;
            }

            if ((System.currentTimeMillis() - waitStart) % 5000 < 500) {
                System.out.printf("   ⏳ Processing: PG=%,d (%.1f%%) | ES=%,d (%.1f%%)%n",
                        pgCount, pgProgress, esCount, esProgress);
            }
            Thread.sleep(500);
        }

        long testEndTime = System.currentTimeMillis();
        long totalDuration = testEndTime - testStartTime;
        long finalPgCount = logRepository.count();
        long finalEsCount = getElasticsearchCount();

        printResults(totalLogsAccepted.get(), finalPgCount, finalEsCount, totalDuration, latencies, intervalMetrics);

        assertThat(finalPgCount).isGreaterThan(0);
        assertThat(finalEsCount).isGreaterThan(0);
    }

    /**
     * TEST 2: Find the maximum sustainable throughput.
     * Measures BOTH PostgreSQL and Elasticsearch.
     */
    @Test
    void findMaxSustainableThroughput() throws InterruptedException {
        System.out.println("\n");
        System.out.println("███╗   ███╗ █████╗ ██╗  ██╗    ████████╗██╗  ██╗██████╗  ██████╗ ██╗   ██╗ ██████╗ ██╗  ██╗██████╗ ██╗   ██╗████████╗");
        System.out.println("████╗ ████║██╔══██╗╚██╗██╔╝    ╚══██╔══╝██║  ██║██╔══██╗██╔═══██╗██║   ██║██╔════╝ ██║  ██║██╔══██╗██║   ██║╚══██╔══╝");
        System.out.println("██╔████╔██║███████║ ╚███╔╝        ██║   ███████║██████╔╝██║   ██║██║   ██║██║  ███╗███████║██████╔╝██║   ██║   ██║   ");
        System.out.println("██║╚██╔╝██║██╔══██║ ██╔██╗        ██║   ██╔══██║██╔══██╗██║   ██║██║   ██║██║   ██║██╔══██║██╔═══╝ ██║   ██║   ██║   ");
        System.out.println("██║ ╚═╝ ██║██║  ██║██╔╝ ██╗       ██║   ██║  ██║██║  ██║╚██████╔╝╚██████╔╝╚██████╔╝██║  ██║██║     ╚██████╔╝   ██║   ");
        System.out.println("╚═╝     ╚═╝╚═╝  ╚═╝╚═╝  ╚═╝       ╚═╝   ╚═╝  ╚═╝╚═╝  ╚═╝ ╚═════╝  ╚═════╝  ╚═════╝ ╚═╝  ╚═╝╚═╝      ╚═════╝    ╚═╝   ");
        System.out.println("=".repeat(100));
        System.out.println("Finding maximum sustainable throughput (PostgreSQL + Elasticsearch)...\n");

        int[] testRates = {100, 250, 500, 1000, 2000, 5000, 10000, 15000, 20000};
        int testDurationPerRate = 10;

        List<RateTestResult> results = new ArrayList<>();

        for (int rate : testRates) {
            logRepository.deleteAll();
            elasticsearchRepository.deleteAll();
            try {
                elasticsearchOperations.indexOps(LogDocument.class).refresh();
            } catch (Exception e) { /* ignore */ }
            Thread.sleep(2000);

            System.out.printf("🧪 Testing %,d logs/sec for %d seconds...%n", rate, testDurationPerRate);

            RateTestResult result = testRate(rate, testDurationPerRate);
            results.add(result);

            System.out.printf("   → PG: %,.0f/s (lag:%,d) | ES: %,.0f/s (lag:%,d) | %s%n",
                    result.pgThroughput, result.pgLag,
                    result.esThroughput, result.esLag,
                    result.sustainable ? "✅ SUSTAINABLE" : "❌ FALLING BEHIND");

            if (!result.sustainable) {
                System.out.println("   ⚠️  System cannot sustain this rate. Stopping escalation.");
                break;
            }
        }

        // Print summary
        System.out.println("\n" + "=".repeat(110));
        System.out.println("RESULTS SUMMARY (End-to-End: API → Kafka → PostgreSQL + Elasticsearch)");
        System.out.println("=".repeat(110));
        System.out.printf("%-12s | %-12s | %-8s | %-12s | %-8s | %-10s | %-10s%n",
                "Target", "PG Rate", "PG Lag", "ES Rate", "ES Lag", "Efficiency", "Status");
        System.out.println("-".repeat(110));

        int maxSustainable = 0;
        for (RateTestResult r : results) {
            // Use the SLOWER of the two as actual throughput (bottleneck)
            double actualRate = Math.min(r.pgThroughput, r.esThroughput);
            double efficiency = (actualRate / r.targetRate) * 100;

            System.out.printf("%-,12d | %-,12.0f | %-,8d | %-,12.0f | %-,8d | %9.1f%% | %-10s%n",
                    r.targetRate,
                    r.pgThroughput, r.pgLag,
                    r.esThroughput, r.esLag,
                    efficiency,
                    r.sustainable ? "✅ OK" : "❌ FAIL");

            if (r.sustainable) {
                maxSustainable = r.targetRate;
            }
        }

        System.out.println("=".repeat(110));
        System.out.printf("🏆 MAXIMUM SUSTAINABLE THROUGHPUT: %,d logs/second (both stores)%n", maxSustainable);
        System.out.printf("📊 Daily Capacity: ~%,d logs/day%n", maxSustainable * 86400L);
        System.out.println("=".repeat(110));
    }

    /**
     * TEST 3: Stress test beyond capacity.
     */
    @Test
    void stressTestBeyondCapacity() throws InterruptedException {
        System.out.println("\n");
        System.out.println("💥 STRESS TEST - Pushing Beyond Capacity (Full Pipeline)");
        System.out.println("=".repeat(80));

        int extremeRate = 20000;
        int stressDuration = 15;

        System.out.printf("🔥 Target: %,d logs/sec for %d seconds%n", extremeRate, stressDuration);
        System.out.printf("   Expected total: %,d logs%n", extremeRate * stressDuration);
        System.out.printf("   Tracking: PostgreSQL + Elasticsearch%n");
        System.out.println("-".repeat(80));

        logRepository.deleteAll();
        elasticsearchRepository.deleteAll();

        AtomicLong totalSent = new AtomicLong(0);
        AtomicLong totalAccepted = new AtomicLong(0);
        AtomicLong totalRejected = new AtomicLong(0);
        List<Long> responseTimes = new CopyOnWriteArrayList<>();

        int batchSize = 500;
        int batchesPerSecond = extremeRate / batchSize;
        long delayMs = Math.max(1, 1000 / batchesPerSecond);

        ExecutorService httpExecutor = Executors.newFixedThreadPool(16);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        long startTime = System.currentTimeMillis();

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            httpExecutor.submit(() -> {
                try {
                    List<LogEntryRequest> batch = generateBatch(batchSize);
                    long reqStart = System.nanoTime();
                    ResponseEntity<String> response = sendBatch(batch);
                    long reqTime = (System.nanoTime() - reqStart) / 1_000_000;
                    responseTimes.add(reqTime);

                    totalSent.addAndGet(batchSize);
                    if (response.getStatusCode().is2xxSuccessful()) {
                        totalAccepted.addAndGet(batchSize);
                    } else {
                        totalRejected.addAndGet(batchSize);
                    }
                } catch (Exception e) {
                    totalRejected.addAndGet(batchSize);
                }
            });
        }, 0, delayMs, TimeUnit.MILLISECONDS);

        for (int i = 0; i < stressDuration; i++) {
            Thread.sleep(1000);
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            long pgCount = logRepository.count();
            long esCount = getElasticsearchCount();
            System.out.printf("   [%2ds] Sent: %,d | PG: %,d | ES: %,d | Rejected: %,d%n",
                    elapsed, totalSent.get(), pgCount, esCount, totalRejected.get());
        }

        task.cancel(false);
        scheduler.shutdown();
        httpExecutor.shutdown();
        scheduler.awaitTermination(5, TimeUnit.SECONDS);
        httpExecutor.awaitTermination(10, TimeUnit.SECONDS);

        System.out.println("\n⏳ Waiting for pipeline to drain...");
        Thread.sleep(10000);

        long finalPgCount = logRepository.count();
        long finalEsCount = getElasticsearchCount();
        long duration = System.currentTimeMillis() - startTime;

        Collections.sort(responseTimes);
        double p50 = responseTimes.isEmpty() ? 0 : responseTimes.get(responseTimes.size() / 2);
        double p95 = responseTimes.isEmpty() ? 0 : responseTimes.get((int)(responseTimes.size() * 0.95));
        double p99 = responseTimes.isEmpty() ? 0 : responseTimes.get((int)(responseTimes.size() * 0.99));

        System.out.println("\n" + "=".repeat(80));
        System.out.println("STRESS TEST RESULTS");
        System.out.println("=".repeat(80));
        System.out.printf("   Logs Sent (API)      : %,d%n", totalSent.get());
        System.out.printf("   Logs Accepted        : %,d%n", totalAccepted.get());
        System.out.printf("   Logs Rejected        : %,d%n", totalRejected.get());
        System.out.println();
        System.out.printf("   PostgreSQL Count     : %,d%n", finalPgCount);
        System.out.printf("   PostgreSQL Rate      : %,.0f logs/sec%n", finalPgCount / (duration / 1000.0));
        System.out.printf("   PostgreSQL Loss      : %.2f%%%n",
                totalAccepted.get() > 0 ? 100.0 * (totalAccepted.get() - finalPgCount) / totalAccepted.get() : 0);
        System.out.println();
        System.out.printf("   Elasticsearch Count  : %,d%n", finalEsCount);
        System.out.printf("   Elasticsearch Rate   : %,.0f logs/sec%n", finalEsCount / (duration / 1000.0));
        System.out.printf("   Elasticsearch Loss   : %.2f%%%n",
                totalAccepted.get() > 0 ? 100.0 * (totalAccepted.get() - finalEsCount) / totalAccepted.get() : 0);
        System.out.println("\n   Response Time Percentiles:");
        System.out.printf("   P50: %.0fms | P95: %.0fms | P99: %.0fms%n", p50, p95, p99);
        System.out.println("=".repeat(80));
    }

    // ==================== HELPER METHODS ====================

    /**
     * Test a specific rate and measure BOTH stores
     */
    private RateTestResult testRate(int targetRate, int durationSeconds) throws InterruptedException {
        AtomicLong totalSent = new AtomicLong(0);
        AtomicLong totalAccepted = new AtomicLong(0);

        int batchSize = Math.min(500, Math.max(100, targetRate / 10));
        int batchesPerSecond = (int) Math.ceil((double) targetRate / batchSize);
        batchesPerSecond = Math.max(1, batchesPerSecond);

        int threadCount = Math.min(16, Math.max(4, batchesPerSecond / 5 + 1));
        ExecutorService httpExecutor = Executors.newFixedThreadPool(threadCount);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        long startTime = System.currentTimeMillis();
        long delayMs = Math.max(1, 1000 / batchesPerSecond);

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            httpExecutor.submit(() -> {
                try {
                    List<LogEntryRequest> batch = generateBatch(batchSize);
                    ResponseEntity<String> response = sendBatch(batch);
                    totalSent.addAndGet(batchSize);
                    if (response.getStatusCode().is2xxSuccessful()) {
                        totalAccepted.addAndGet(batchSize);
                    }
                } catch (Exception ignored) {}
            });
        }, 0, delayMs, TimeUnit.MILLISECONDS);

        Thread.sleep(durationSeconds * 1000L);
        task.cancel(false);
        scheduler.shutdown();
        httpExecutor.shutdown();
        scheduler.awaitTermination(2, TimeUnit.SECONDS);
        httpExecutor.awaitTermination(5, TimeUnit.SECONDS);

        // Wait for BOTH stores
        long expectedLogs = totalAccepted.get();
        long maxWaitMs = Math.max(10000, expectedLogs / 1000 * 200);
        long waitStart = System.currentTimeMillis();

        while (System.currentTimeMillis() - waitStart < maxWaitMs) {
            long pgCount = logRepository.count();
            long esCount = getElasticsearchCount();

            // Both need to be at 95%+
            if (pgCount >= expectedLogs * 0.95 && esCount >= expectedLogs * 0.95) {
                break;
            }
            Thread.sleep(200);
        }

        long pgCount = logRepository.count();
        long esCount = getElasticsearchCount();
        long pgLag = totalAccepted.get() - pgCount;
        long esLag = totalAccepted.get() - esCount;

        double pgThroughput = pgCount / (double) durationSeconds;
        double esThroughput = esCount / (double) durationSeconds;

        // Sustainable if BOTH stores have lag < 10%
        boolean sustainable = pgLag < (totalAccepted.get() * 0.1) &&
                esLag < (totalAccepted.get() * 0.1);

        return new RateTestResult(targetRate, pgThroughput, pgLag, esThroughput, esLag, sustainable);
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
                            "region", random.nextBoolean() ? "us-east-1" : "eu-west-1",
                            "latency_ms", random.nextInt(500)
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
        System.out.println("███████╗██╗   ██╗███████╗████████╗ █████╗ ██╗███╗   ██╗███████╗██████╗ ");
        System.out.println("██╔════╝██║   ██║██╔════╝╚══██╔══╝██╔══██╗██║████╗  ██║██╔════╝██╔══██╗");
        System.out.println("███████╗██║   ██║███████╗   ██║   ███████║██║██╔██╗ ██║█████╗  ██║  ██║");
        System.out.println("╚════██║██║   ██║╚════██║   ██║   ██╔══██║██║██║╚██╗██║██╔══╝  ██║  ██║");
        System.out.println("███████║╚██████╔╝███████║   ██║   ██║  ██║██║██║ ╚████║███████╗██████╔╝");
        System.out.println("╚══════╝ ╚═════╝ ╚══════╝   ╚═╝   ╚═╝  ╚═╝╚═╝╚═╝  ╚═══╝╚══════╝╚═════╝ ");
        System.out.println("         END-TO-END THROUGHPUT TEST (PostgreSQL + Elasticsearch)       ");
        System.out.println("=".repeat(80));
    }

    private void printResults(long totalSent, long pgCount, long esCount, long duration,
                              List<Long> latencies, List<IntervalMetrics> intervals) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("FINAL RESULTS");
        System.out.println("=".repeat(80));

        double pgThroughput = pgCount / (duration / 1000.0);
        double esThroughput = esCount / (duration / 1000.0);
        double pgEfficiency = totalSent > 0 ? ((double) pgCount / totalSent) * 100 : 0;
        double esEfficiency = totalSent > 0 ? ((double) esCount / totalSent) * 100 : 0;

        System.out.printf("%-30s : %,d%n", "Logs Accepted by API", totalSent);
        System.out.printf("%-30s : %,d ms%n", "Total Test Duration", duration);
        System.out.println();
        System.out.println("📦 PostgreSQL:");
        System.out.printf("   %-26s : %,d%n", "Final Count", pgCount);
        System.out.printf("   %-26s : %,.2f logs/sec%n", "Throughput", pgThroughput);
        System.out.printf("   %-26s : %.1f%%%n", "Efficiency", pgEfficiency);
        System.out.println();
        System.out.println("🔍 Elasticsearch:");
        System.out.printf("   %-26s : %,d%n", "Final Count", esCount);
        System.out.printf("   %-26s : %,.2f logs/sec%n", "Throughput", esThroughput);
        System.out.printf("   %-26s : %.1f%%%n", "Efficiency", esEfficiency);

        if (!latencies.isEmpty()) {
            Collections.sort(latencies);
            System.out.println("\n📊 API Latency (time to accept batch):");
            System.out.printf("   P50  : %d ms%n", latencies.get(latencies.size() / 2));
            System.out.printf("   P95  : %d ms%n", latencies.get((int) (latencies.size() * 0.95)));
            System.out.printf("   P99  : %d ms%n", latencies.get((int) (latencies.size() * 0.99)));
            System.out.printf("   Max  : %d ms%n", latencies.get(latencies.size() - 1));
        }

        if (!intervals.isEmpty()) {
            System.out.println("\n📈 Throughput Over Time:");
            double avgThroughput = intervals.stream()
                    .mapToDouble(i -> i.throughput)
                    .average()
                    .orElse(0);

            System.out.printf("   Average API Rate : %,.0f logs/sec%n", avgThroughput);

            // Check for degradation
            if (intervals.size() >= 4) {
                double firstHalf = intervals.subList(0, intervals.size() / 2).stream()
                        .mapToDouble(i -> i.throughput).average().orElse(0);
                double secondHalf = intervals.subList(intervals.size() / 2, intervals.size()).stream()
                        .mapToDouble(i -> i.throughput).average().orElse(0);

                if (secondHalf < firstHalf * 0.9) {
                    System.out.println("   ⚠️  WARNING: Throughput degraded over time");
                } else {
                    System.out.println("   ✅ Throughput remained stable throughout test");
                }
            }

            // Lag analysis for BOTH stores
            long maxPgLag = intervals.stream().mapToLong(i -> i.pgLag).max().orElse(0);
            long maxEsLag = intervals.stream().mapToLong(i -> i.esLag).max().orElse(0);
            long finalPgLag = intervals.get(intervals.size() - 1).pgLag;
            long finalEsLag = intervals.get(intervals.size() - 1).esLag;

            System.out.println("\n📉 Consumer Lag Analysis:");
            System.out.printf("   PostgreSQL    - Max: %,d | Final: %,d%n", maxPgLag, finalPgLag);
            System.out.printf("   Elasticsearch - Max: %,d | Final: %,d%n", maxEsLag, finalEsLag);

            if (maxPgLag > totalSent * 0.2 || maxEsLag > totalSent * 0.2) {
                System.out.println("   ⚠️  WARNING: High lag detected. System falling behind.");
            } else if (maxPgLag > totalSent * 0.1 || maxEsLag > totalSent * 0.1) {
                System.out.println("   ⚡ NOTICE: Moderate lag. System is catching up.");
            } else {
                System.out.println("   ✅ Both stores keeping pace with ingestion.");
            }
        }

        // Use SLOWER store for recommendation
        double effectiveThroughput = Math.min(pgThroughput, esThroughput);

        System.out.println("\n" + "=".repeat(80));
        System.out.printf("🎯 RECOMMENDATION: Based on end-to-end results, your system can sustainably%n");
        System.out.printf("   handle approximately %,.0f logs/second with both stores.%n", effectiveThroughput * 0.8);
        System.out.println("=".repeat(80));
    }

    // ==================== INNER CLASSES ====================

    private record IntervalMetrics(
            long elapsedSeconds,
            long logsSent,
            double throughput,
            long pgCount,
            long esCount,
            long pgLag,
            long esLag
    ) {}

    private record RateTestResult(
            int targetRate,
            double pgThroughput,
            long pgLag,
            double esThroughput,
            long esLag,
            boolean sustainable
    ) {}
}
