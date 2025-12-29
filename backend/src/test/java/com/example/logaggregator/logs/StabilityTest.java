package com.example.logaggregator.logs;

import com.example.logaggregator.BaseIntegrationTest;
import com.example.logaggregator.elasticsearch.LogDocument;
import com.example.logaggregator.elasticsearch.LogElasticsearchRepository;
import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import com.example.logaggregator.logs.models.LogEntry;
import com.example.logaggregator.logs.models.LogStatus;
import com.example.logaggregator.logs.services.LogPostgresSearchService;
import com.example.logaggregator.elasticsearch.services.LogElasticsearchSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.Page;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STABILITY TEST - More Realistic Production Simulation
 *
 * Key differences from ConstantLoadTest:
 * 1. Runs for MINUTES, not seconds (catches memory leaks, GC issues)
 * 2. Includes CONCURRENT READS while writing (realistic load pattern)
 * 3. Monitors LAG TREND over time (is it growing or stable?)
 * 4. Checks for DEGRADATION (does performance drop over time?)
 * 5. Lower target rate but must be TRULY sustainable
 *
 * This tells you what you can ACTUALLY run in production 24/7.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("stability-test")
@ActiveProfiles("test")
public class StabilityTest extends BaseIntegrationTest {

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

    @Autowired
    private LogPostgresSearchService postgresSearchService;

    @Autowired
    private LogElasticsearchSearchService elasticsearchSearchService;

    // ==================== CONFIGURATION ====================
    // Conservative settings for TRUE sustainability

    /** Target rate - intentionally lower than max to test sustainability */
    private static final int TARGET_LOGS_PER_SECOND = 2000;

    /** Test duration in MINUTES (not seconds!) */
    private static final int TEST_DURATION_MINUTES = 5;

    /** How often to report metrics (seconds) */
    private static final int REPORT_INTERVAL_SECONDS = 10;

    /** Concurrent read threads (simulates users searching) */
    private static final int CONCURRENT_READERS = 5;

    /** Batch size for writes */
    private static final int BATCH_SIZE = 200;

    /** Max acceptable lag as percentage of total sent */
    private static final double MAX_LAG_PERCENT = 5.0;

    /** Max acceptable throughput degradation */
    private static final double MAX_DEGRADATION_PERCENT = 20.0;

    private final Random random = new Random();
    private final String[] services = {"auth-service", "payment-service", "notification-service",
            "user-service", "inventory-service", "shipping-service"};
    private final LogStatus[] levels = {LogStatus.INFO, LogStatus.DEBUG, LogStatus.WARNING, LogStatus.ERROR};

    @BeforeEach
    void cleanup() {
        logRepository.deleteAll();
        elasticsearchRepository.deleteAll();
        try {
            elasticsearchOperations.indexOps(LogDocument.class).refresh();
        } catch (Exception e) { /* ignore */ }
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
     * MAIN TEST: Sustained load with concurrent reads
     *
     * This is the test that tells you your REAL production capacity.
     */
    @Test
    void sustainedLoadWithConcurrentReads() throws InterruptedException {
        System.out.println("\n");
        System.out.println("███████╗████████╗ █████╗ ██████╗ ██╗██╗     ██╗████████╗██╗   ██╗");
        System.out.println("██╔════╝╚══██╔══╝██╔══██╗██╔══██╗██║██║     ██║╚══██╔══╝╚██╗ ██╔╝");
        System.out.println("███████╗   ██║   ███████║██████╔╝██║██║     ██║   ██║    ╚████╔╝ ");
        System.out.println("╚════██║   ██║   ██╔══██║██╔══██╗██║██║     ██║   ██║     ╚██╔╝  ");
        System.out.println("███████║   ██║   ██║  ██║██████╔╝██║███████╗██║   ██║      ██║   ");
        System.out.println("╚══════╝   ╚═╝   ╚═╝  ╚═╝╚═════╝ ╚═╝╚══════╝╚═╝   ╚═╝      ╚═╝   ");
        System.out.println("        PRODUCTION STABILITY TEST - Sustained Load + Concurrent Reads");
        System.out.println("=".repeat(90));
        System.out.println();
        System.out.printf("📊 Configuration:%n");
        System.out.printf("   Target Write Rate    : %,d logs/sec%n", TARGET_LOGS_PER_SECOND);
        System.out.printf("   Test Duration        : %d minutes%n", TEST_DURATION_MINUTES);
        System.out.printf("   Concurrent Readers   : %d threads%n", CONCURRENT_READERS);
        System.out.printf("   Expected Total Logs  : %,d%n", TARGET_LOGS_PER_SECOND * TEST_DURATION_MINUTES * 60);
        System.out.printf("   Max Acceptable Lag   : %.1f%%%n", MAX_LAG_PERCENT);
        System.out.printf("   Max Degradation      : %.1f%%%n", MAX_DEGRADATION_PERCENT);
        System.out.println();
        System.out.println("This test simulates REAL production: writes + concurrent searches.");
        System.out.println("-".repeat(90));

        // Metrics tracking
        AtomicLong totalWritten = new AtomicLong(0);
        AtomicLong totalReadQueries = new AtomicLong(0);
        AtomicLong totalReadLatencyMs = new AtomicLong(0);
        List<StabilityMetrics> metricsHistory = new CopyOnWriteArrayList<>();

        // Thread pools
        ExecutorService writeExecutor = Executors.newFixedThreadPool(8);
        ExecutorService readExecutor = Executors.newFixedThreadPool(CONCURRENT_READERS);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

        int batchesPerSecond = (int) Math.ceil((double) TARGET_LOGS_PER_SECOND / BATCH_SIZE);
        long writeDelayMs = Math.max(1, 1000 / batchesPerSecond);

        long testStartTime = System.currentTimeMillis();
        long testDurationMs = TEST_DURATION_MINUTES * 60 * 1000L;

        // === WRITE TASK ===
        ScheduledFuture<?> writeTask = scheduler.scheduleAtFixedRate(() -> {
            writeExecutor.submit(() -> {
                try {
                    List<LogEntryRequest> batch = generateBatch(BATCH_SIZE);
                    ResponseEntity<String> response = sendBatch(batch);
                    if (response.getStatusCode().is2xxSuccessful()) {
                        totalWritten.addAndGet(BATCH_SIZE);
                    }
                } catch (Exception e) {
                    // Log but continue
                }
            });
        }, 0, writeDelayMs, TimeUnit.MILLISECONDS);

        // === READ TASKS (Concurrent searches) ===
        for (int i = 0; i < CONCURRENT_READERS; i++) {
            final int readerId = i;
            readExecutor.submit(() -> {
                while (System.currentTimeMillis() - testStartTime < testDurationMs) {
                    try {
                        // Random search query
                        LogSearchRequest searchRequest = generateRandomSearch();

                        long queryStart = System.currentTimeMillis();

                        // Alternate between PG and ES searches
                        if (readerId % 2 == 0) {
                            postgresSearchService.search(searchRequest);
                        } else {
                            elasticsearchSearchService.search(searchRequest);
                        }

                        long queryTime = System.currentTimeMillis() - queryStart;
                        totalReadQueries.incrementAndGet();
                        totalReadLatencyMs.addAndGet(queryTime);

                        // Small delay between queries
                        Thread.sleep(100 + random.nextInt(200));

                    } catch (Exception e) {
                        // Continue on error
                    }
                }
            });
        }

        // === METRICS REPORTING TASK ===
        AtomicLong lastPgCount = new AtomicLong(0);
        AtomicLong lastEsCount = new AtomicLong(0);
        AtomicLong lastReportTime = new AtomicLong(testStartTime);

        ScheduledFuture<?> reportTask = scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            long elapsed = (now - testStartTime) / 1000;
            long intervalMs = now - lastReportTime.get();
            lastReportTime.set(now);

            long pgCount = logRepository.count();
            long esCount = getElasticsearchCount();

            // Calculate interval throughput
            long pgDelta = pgCount - lastPgCount.get();
            long esDelta = esCount - lastEsCount.get();
            lastPgCount.set(pgCount);
            lastEsCount.set(esCount);

            double pgThroughput = intervalMs > 0 ? (pgDelta * 1000.0 / intervalMs) : 0;
            double esThroughput = intervalMs > 0 ? (esDelta * 1000.0 / intervalMs) : 0;

            long written = totalWritten.get();
            long pgLag = written - pgCount;
            long esLag = written - esCount;
            double pgLagPercent = written > 0 ? (pgLag * 100.0 / written) : 0;
            double esLagPercent = written > 0 ? (esLag * 100.0 / written) : 0;

            long queries = totalReadQueries.get();
            double avgReadLatency = queries > 0 ? (totalReadLatencyMs.get() / (double) queries) : 0;

            StabilityMetrics metrics = new StabilityMetrics(
                    elapsed, written, pgCount, esCount,
                    pgThroughput, esThroughput,
                    pgLag, esLag, pgLagPercent, esLagPercent,
                    queries, avgReadLatency
            );
            metricsHistory.add(metrics);

            // Print status
            String lagStatus = (pgLagPercent > MAX_LAG_PERCENT || esLagPercent > MAX_LAG_PERCENT) ? "⚠️" : "✅";
            System.out.printf("%s [%3dm %02ds] Write: %,d | PG: %,.0f/s (lag:%.1f%%) | ES: %,.0f/s (lag:%.1f%%) | Reads: %,d (avg:%.0fms)%n",
                    lagStatus,
                    elapsed / 60, elapsed % 60,
                    written,
                    pgThroughput, pgLagPercent,
                    esThroughput, esLagPercent,
                    queries, avgReadLatency);

        }, REPORT_INTERVAL_SECONDS, REPORT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // === WAIT FOR TEST COMPLETION ===
        Thread.sleep(testDurationMs);

        // Shutdown
        writeTask.cancel(false);
        reportTask.cancel(false);
        scheduler.shutdown();
        writeExecutor.shutdown();
        readExecutor.shutdownNow(); // Interrupt readers

        scheduler.awaitTermination(5, TimeUnit.SECONDS);
        writeExecutor.awaitTermination(5, TimeUnit.SECONDS);

        // Wait for pipeline to drain
        System.out.println("\n⏳ Waiting for pipeline to fully drain...");
        long drainStart = System.currentTimeMillis();
        long maxDrainMs = 60000; // 1 minute max

        while (System.currentTimeMillis() - drainStart < maxDrainMs) {
            long pgCount = logRepository.count();
            long esCount = getElasticsearchCount();
            long written = totalWritten.get();

            if (pgCount >= written * 0.99 && esCount >= written * 0.99) {
                System.out.printf("   ✅ Drained: PG=%,d ES=%,d of %,d written%n", pgCount, esCount, written);
                break;
            }

            if ((System.currentTimeMillis() - drainStart) % 10000 < 500) {
                System.out.printf("   ⏳ Draining: PG=%,d ES=%,d of %,d...%n", pgCount, esCount, written);
            }
            Thread.sleep(500);
        }

        // === FINAL ANALYSIS ===
        printStabilityAnalysis(metricsHistory, totalWritten.get(), totalReadQueries.get());
    }

    private void printStabilityAnalysis(List<StabilityMetrics> history, long totalWritten, long totalReads) {
        System.out.println("\n" + "=".repeat(90));
        System.out.println("STABILITY ANALYSIS");
        System.out.println("=".repeat(90));

        if (history.isEmpty()) {
            System.out.println("❌ No metrics collected!");
            return;
        }

        long finalPgCount = logRepository.count();
        long finalEsCount = getElasticsearchCount();

        // Basic stats
        System.out.println("\n📊 THROUGHPUT SUMMARY:");
        System.out.printf("   Total Logs Written   : %,d%n", totalWritten);
        System.out.printf("   PostgreSQL Final     : %,d (%.1f%%)%n", finalPgCount, (finalPgCount * 100.0 / totalWritten));
        System.out.printf("   Elasticsearch Final  : %,d (%.1f%%)%n", finalEsCount, (finalEsCount * 100.0 / totalWritten));
        System.out.printf("   Total Read Queries   : %,d%n", totalReads);

        // Throughput analysis
        double avgPgThroughput = history.stream().mapToDouble(m -> m.pgThroughput).average().orElse(0);
        double avgEsThroughput = history.stream().mapToDouble(m -> m.esThroughput).average().orElse(0);
        double minPgThroughput = history.stream().mapToDouble(m -> m.pgThroughput).min().orElse(0);
        double minEsThroughput = history.stream().mapToDouble(m -> m.esThroughput).min().orElse(0);

        System.out.println("\n📈 THROUGHPUT STABILITY:");
        System.out.printf("   PostgreSQL     - Avg: %,.0f/s | Min: %,.0f/s%n", avgPgThroughput, minPgThroughput);
        System.out.printf("   Elasticsearch  - Avg: %,.0f/s | Min: %,.0f/s%n", avgEsThroughput, minEsThroughput);

        // Check for degradation (compare first half vs second half)
        if (history.size() >= 4) {
            int mid = history.size() / 2;
            double firstHalfPg = history.subList(0, mid).stream().mapToDouble(m -> m.pgThroughput).average().orElse(0);
            double secondHalfPg = history.subList(mid, history.size()).stream().mapToDouble(m -> m.pgThroughput).average().orElse(0);
            double firstHalfEs = history.subList(0, mid).stream().mapToDouble(m -> m.esThroughput).average().orElse(0);
            double secondHalfEs = history.subList(mid, history.size()).stream().mapToDouble(m -> m.esThroughput).average().orElse(0);

            double pgDegradation = firstHalfPg > 0 ? ((firstHalfPg - secondHalfPg) / firstHalfPg * 100) : 0;
            double esDegradation = firstHalfEs > 0 ? ((firstHalfEs - secondHalfEs) / firstHalfEs * 100) : 0;

            System.out.println("\n🔄 DEGRADATION CHECK (First Half vs Second Half):");
            System.out.printf("   PostgreSQL     : %.1f%% %s%n", pgDegradation,
                    pgDegradation > MAX_DEGRADATION_PERCENT ? "⚠️ DEGRADING" : "✅ STABLE");
            System.out.printf("   Elasticsearch  : %.1f%% %s%n", esDegradation,
                    esDegradation > MAX_DEGRADATION_PERCENT ? "⚠️ DEGRADING" : "✅ STABLE");
        }

        // Lag analysis
        double maxPgLagPercent = history.stream().mapToDouble(m -> m.pgLagPercent).max().orElse(0);
        double maxEsLagPercent = history.stream().mapToDouble(m -> m.esLagPercent).max().orElse(0);
        double avgPgLagPercent = history.stream().mapToDouble(m -> m.pgLagPercent).average().orElse(0);
        double avgEsLagPercent = history.stream().mapToDouble(m -> m.esLagPercent).average().orElse(0);

        // Check if lag is GROWING (bad) or STABLE (ok)
        boolean pgLagGrowing = false;
        boolean esLagGrowing = false;
        if (history.size() >= 4) {
            int mid = history.size() / 2;
            double firstHalfPgLag = history.subList(0, mid).stream().mapToDouble(m -> m.pgLagPercent).average().orElse(0);
            double secondHalfPgLag = history.subList(mid, history.size()).stream().mapToDouble(m -> m.pgLagPercent).average().orElse(0);
            double firstHalfEsLag = history.subList(0, mid).stream().mapToDouble(m -> m.esLagPercent).average().orElse(0);
            double secondHalfEsLag = history.subList(mid, history.size()).stream().mapToDouble(m -> m.esLagPercent).average().orElse(0);

            pgLagGrowing = secondHalfPgLag > firstHalfPgLag * 1.5;
            esLagGrowing = secondHalfEsLag > firstHalfEsLag * 1.5;
        }

        System.out.println("\n📉 LAG ANALYSIS:");
        System.out.printf("   PostgreSQL     - Avg: %.1f%% | Max: %.1f%% | Trend: %s%n",
                avgPgLagPercent, maxPgLagPercent, pgLagGrowing ? "📈 GROWING ⚠️" : "📊 STABLE ✅");
        System.out.printf("   Elasticsearch  - Avg: %.1f%% | Max: %.1f%% | Trend: %s%n",
                avgEsLagPercent, maxEsLagPercent, esLagGrowing ? "📈 GROWING ⚠️" : "📊 STABLE ✅");

        // Read performance
        double avgReadLatency = history.stream().mapToDouble(m -> m.avgReadLatency).average().orElse(0);
        double maxReadLatency = history.stream().mapToDouble(m -> m.avgReadLatency).max().orElse(0);

        System.out.println("\n🔍 READ PERFORMANCE (Under Write Load):");
        System.out.printf("   Avg Query Latency : %.0f ms%n", avgReadLatency);
        System.out.printf("   Max Query Latency : %.0f ms%n", maxReadLatency);
        System.out.printf("   Total Queries     : %,d%n", totalReads);

        // === FINAL VERDICT ===
        System.out.println("\n" + "=".repeat(90));
        System.out.println("VERDICT");
        System.out.println("=".repeat(90));

        boolean lagAcceptable = maxPgLagPercent <= MAX_LAG_PERCENT && maxEsLagPercent <= MAX_LAG_PERCENT;
        boolean lagStable = !pgLagGrowing && !esLagGrowing;
        boolean throughputStable = minPgThroughput >= avgPgThroughput * 0.7 && minEsThroughput >= avgEsThroughput * 0.7;

        double effectiveThroughput = Math.min(avgPgThroughput, avgEsThroughput);

        if (lagAcceptable && lagStable && throughputStable) {
            System.out.printf("✅ PASSED: System is STABLE at %,d logs/sec with concurrent reads%n", TARGET_LOGS_PER_SECOND);
            System.out.printf("   Recommended production rate: %,.0f logs/sec (80%% of tested)%n", effectiveThroughput * 0.8);
        } else {
            System.out.println("⚠️  WARNING: Stability issues detected:");
            if (!lagAcceptable) {
                System.out.printf("   - Lag exceeded %.1f%% threshold (PG: %.1f%%, ES: %.1f%%)%n",
                        MAX_LAG_PERCENT, maxPgLagPercent, maxEsLagPercent);
            }
            if (!lagStable) {
                System.out.println("   - Lag is GROWING over time (not sustainable)");
            }
            if (!throughputStable) {
                System.out.println("   - Throughput dropped significantly during test");
            }
            System.out.printf("   Recommended production rate: %,.0f logs/sec (conservative)%n", effectiveThroughput * 0.5);
        }

        System.out.println("\n💡 FOR TRUE PRODUCTION CAPACITY:");
        System.out.println("   1. Run this test for 10+ minutes");
        System.out.println("   2. Monitor Docker memory/CPU during test");
        System.out.println("   3. Check if lag GROWS over time (unsustainable)");
        System.out.println("   4. Test with your actual Docker Compose, not Testcontainers");
        System.out.println("=".repeat(90));

        // Assertions
        assertThat(finalPgCount).isGreaterThan((long)(totalWritten * 0.95));
        assertThat(finalEsCount).isGreaterThan((long)(totalWritten * 0.95));
    }

    private LogSearchRequest generateRandomSearch() {
        // Random search parameters
        String serviceId = random.nextBoolean() ? services[random.nextInt(services.length)] : null;
        LogStatus level = random.nextBoolean() ? levels[random.nextInt(levels.length)] : null;
        String query = random.nextBoolean() ? "error" : null;

        Instant endTime = Instant.now();
        Instant startTime = endTime.minus(1, ChronoUnit.HOURS);

        return new LogSearchRequest(
                serviceId, level, null, startTime, endTime, query, 0, 20
        );
    }

    private List<LogEntryRequest> generateBatch(int size) {
        List<LogEntryRequest> batch = new ArrayList<>(size);
        Instant now = Instant.now();

        for (int i = 0; i < size; i++) {
            batch.add(new LogEntryRequest(
                    now.minusMillis(random.nextInt(1000)),
                    services[random.nextInt(services.length)],
                    levels[random.nextInt(levels.length)],
                    "Test message " + UUID.randomUUID().toString().substring(0, 8),
                    Map.of("requestId", "req-" + random.nextInt(10000)),
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

    private record StabilityMetrics(
            long elapsedSeconds,
            long written,
            long pgCount,
            long esCount,
            double pgThroughput,
            double esThroughput,
            long pgLag,
            long esLag,
            double pgLagPercent,
            double esLagPercent,
            long readQueries,
            double avgReadLatency
    ) {}
}
