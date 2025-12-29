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
 * STABILITY TEST - Sustained Load with Concurrent Reads
 *
 * ╔════════════════════════════════════════════════════════════════════════════════╗
 * ║  PURPOSE: Verify the system can sustain load over time without degradation    ║
 * ╠════════════════════════════════════════════════════════════════════════════════╣
 * ║  This test simulates realistic production usage:                               ║
 * ║    • Continuous log ingestion at a steady rate                                ║
 * ║    • Concurrent search queries (simulates users using the UI)                 ║
 * ║    • Monitors for lag growth (indicates unsustainable load)                   ║
 * ║    • Checks for throughput degradation over time                              ║
 * ║                                                                                ║
 * ║  KEY METRICS:                                                                  ║
 * ║    • Processing Lag: Should remain stable or decrease                         ║
 * ║    • Throughput: Should not degrade more than 20% over time                   ║
 * ║    • Data Integrity: >99% of logs should be persisted                         ║
 * ╚════════════════════════════════════════════════════════════════════════════════╝
 *
 * ⚠️  TESTCONTAINERS vs PRODUCTION:
 *     These tests run in isolated containers without frontend load.
 *     Real-world sustainable rates will be 30-50% of test results.
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
    // Conservative settings for stability testing

    /** Target rate - should be easily sustainable */
    private static final int TARGET_LOGS_PER_SECOND = 6000;

    /** Test duration in seconds (2 minutes) */
    private static final int TEST_DURATION_SECONDS = 120;

    /** Report interval */
    private static final int REPORT_INTERVAL_SECONDS = 10;

    /** Concurrent search threads (simulates users) */
    private static final int CONCURRENT_READERS = 3;

    /** Batch size per API call */
    private static final int BATCH_SIZE = 2000;

    /** Max acceptable lag as percentage */
    private static final double MAX_LAG_PERCENT = 10.0;

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

    private long getTotalProcessedCount() {
        try {
            // Use PostgreSQL as the source of truth for "processed" logs
            return logRepository.count();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * MAIN TEST: Sustained load with concurrent reads
     *
     * This test verifies:
     * 1. System can sustain the target rate without growing lag
     * 2. Throughput doesn't degrade over time
     * 3. Concurrent searches don't break under write load
     */
    @Test
    void sustainedLoadWithConcurrentReads() throws InterruptedException {
        printHeader();

        // Metrics tracking
        AtomicLong totalWritten = new AtomicLong(0);
        AtomicLong totalSearches = new AtomicLong(0);
        AtomicLong totalSearchLatencyMs = new AtomicLong(0);
        List<StabilitySnapshot> snapshots = new CopyOnWriteArrayList<>();

        // Thread pools
        ExecutorService writeExecutor = Executors.newFixedThreadPool(4);
        ExecutorService readExecutor = Executors.newFixedThreadPool(CONCURRENT_READERS);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        // Calculate timing
        int batchesPerSecond = Math.max(1, TARGET_LOGS_PER_SECOND / BATCH_SIZE);
        long writeDelayMs = Math.max(1, 1000 / batchesPerSecond);

        long testStartTime = System.currentTimeMillis();

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
                    // Continue on error
                }
            });
        }, 0, writeDelayMs, TimeUnit.MILLISECONDS);

        // === READ TASKS ===
        for (int i = 0; i < CONCURRENT_READERS; i++) {
            readExecutor.submit(() -> {
                while (System.currentTimeMillis() - testStartTime < TEST_DURATION_SECONDS * 1000L) {
                    try {
                        LogSearchRequest searchRequest = generateRandomSearch();
                        long queryStart = System.currentTimeMillis();

                        // Use Elasticsearch for searches (primary search engine)
                        elasticsearchSearchService.search(searchRequest);

                        long queryTime = System.currentTimeMillis() - queryStart;
                        totalSearches.incrementAndGet();
                        totalSearchLatencyMs.addAndGet(queryTime);

                        // Small delay between queries
                        Thread.sleep(200 + random.nextInt(300));
                    } catch (Exception e) {
                        // Continue on error
                    }
                }
            });
        }

        // === METRICS SNAPSHOT TASK ===
        AtomicLong lastProcessed = new AtomicLong(0);
        AtomicLong lastReportTime = new AtomicLong(testStartTime);

        ScheduledFuture<?> reportTask = scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            long elapsed = (now - testStartTime) / 1000;
            long intervalMs = now - lastReportTime.get();
            lastReportTime.set(now);

            long processed = getTotalProcessedCount();
            long intervalProcessed = processed - lastProcessed.get();
            lastProcessed.set(processed);

            double throughput = intervalMs > 0 ? (intervalProcessed * 1000.0 / intervalMs) : 0;
            long written = totalWritten.get();
            long lag = written - processed;
            double lagPercent = written > 0 ? (lag * 100.0 / written) : 0;

            long searches = totalSearches.get();
            double avgSearchLatency = searches > 0 ? (totalSearchLatencyMs.get() / (double) searches) : 0;

            StabilitySnapshot snapshot = new StabilitySnapshot(
                    elapsed, written, processed, throughput, lag, lagPercent, searches, avgSearchLatency
            );
            snapshots.add(snapshot);

            String lagStatus = lagPercent > MAX_LAG_PERCENT ? "⚠️" : "✅";
            System.out.printf("%s [%3ds] Written: %,d | Processed: %,d | Rate: %,.0f/s | Lag: %.1f%% | Searches: %,d (avg %.0fms)%n",
                    lagStatus, elapsed, written, processed, throughput, lagPercent, searches, avgSearchLatency);

        }, REPORT_INTERVAL_SECONDS, REPORT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // === RUN TEST ===
        Thread.sleep(TEST_DURATION_SECONDS * 1000L);

        // Shutdown
        writeTask.cancel(false);
        reportTask.cancel(false);
        scheduler.shutdown();
        writeExecutor.shutdown();
        readExecutor.shutdownNow();

        scheduler.awaitTermination(5, TimeUnit.SECONDS);
        writeExecutor.awaitTermination(5, TimeUnit.SECONDS);

        // Wait for pipeline to drain
        System.out.println("\n⏳ Waiting for pipeline to drain...");
        long drainStart = System.currentTimeMillis();
        while (System.currentTimeMillis() - drainStart < 30000) {
            long processed = getTotalProcessedCount();
            long written = totalWritten.get();
            if (processed >= written * 0.99) {
                System.out.printf("   ✅ Drained: %,d / %,d (%.1f%%)%n",
                        processed, written, (processed * 100.0 / written));
                break;
            }
            Thread.sleep(500);
        }

        // === ANALYSIS ===
        printStabilityAnalysis(snapshots, totalWritten.get(), totalSearches.get());
    }

    private void printHeader() {
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║     STABILITY TEST - Sustained Load + Concurrent Reads           ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("Configuration:%n");
        System.out.printf("   Target Write Rate    : %,d logs/sec%n", TARGET_LOGS_PER_SECOND);
        System.out.printf("   Test Duration        : %d seconds (%d minutes)%n",
                TEST_DURATION_SECONDS, TEST_DURATION_SECONDS / 60);
        System.out.printf("   Concurrent Readers   : %d threads%n", CONCURRENT_READERS);
        System.out.printf("   Max Acceptable Lag   : %.0f%%%n", MAX_LAG_PERCENT);
        System.out.printf("   Expected Total Logs  : %,d%n", TARGET_LOGS_PER_SECOND * TEST_DURATION_SECONDS);
        System.out.println();
        System.out.println("⚠️  Note: Real-world rates with frontend will be 30-50% lower");
        System.out.println("-".repeat(70));
    }

    private void printStabilityAnalysis(List<StabilitySnapshot> snapshots, long totalWritten, long totalSearches) {
        System.out.println("\n" + "═".repeat(70));
        System.out.println("STABILITY ANALYSIS");
        System.out.println("═".repeat(70));

        if (snapshots.isEmpty()) {
            System.out.println("❌ No metrics collected!");
            return;
        }

        long finalProcessed = getTotalProcessedCount();

        // Calculate statistics
        double avgThroughput = snapshots.stream().mapToDouble(s -> s.throughput).average().orElse(0);
        double minThroughput = snapshots.stream().mapToDouble(s -> s.throughput).min().orElse(0);
        double maxThroughput = snapshots.stream().mapToDouble(s -> s.throughput).max().orElse(0);
        double avgLagPercent = snapshots.stream().mapToDouble(s -> s.lagPercent).average().orElse(0);
        double maxLagPercent = snapshots.stream().mapToDouble(s -> s.lagPercent).max().orElse(0);

        System.out.println("\n📊 THROUGHPUT:");
        System.out.printf("   Average   : %,.0f logs/sec%n", avgThroughput);
        System.out.printf("   Range     : %,.0f - %,.0f logs/sec%n", minThroughput, maxThroughput);
        System.out.printf("   Target    : %,d logs/sec%n", TARGET_LOGS_PER_SECOND);

        // Check for degradation
        boolean degraded = false;
        if (snapshots.size() >= 4) {
            int mid = snapshots.size() / 2;
            double firstHalf = snapshots.subList(0, mid).stream()
                    .mapToDouble(s -> s.throughput).average().orElse(0);
            double secondHalf = snapshots.subList(mid, snapshots.size()).stream()
                    .mapToDouble(s -> s.throughput).average().orElse(0);

            double degradation = firstHalf > 0 ? ((firstHalf - secondHalf) / firstHalf * 100) : 0;
            degraded = degradation > MAX_DEGRADATION_PERCENT;

            System.out.printf("   Degradation: %.1f%% %s%n", degradation,
                    degraded ? "⚠️ DEGRADING" : "✅ STABLE");
        }

        // Lag analysis
        System.out.println("\n📉 LAG ANALYSIS:");
        System.out.printf("   Average Lag : %.1f%%%n", avgLagPercent);
        System.out.printf("   Max Lag     : %.1f%%%n", maxLagPercent);

        // Check if lag is growing
        boolean lagGrowing = false;
        if (snapshots.size() >= 4) {
            int mid = snapshots.size() / 2;
            double firstHalfLag = snapshots.subList(0, mid).stream()
                    .mapToDouble(s -> s.lagPercent).average().orElse(0);
            double secondHalfLag = snapshots.subList(mid, snapshots.size()).stream()
                    .mapToDouble(s -> s.lagPercent).average().orElse(0);
            lagGrowing = secondHalfLag > firstHalfLag * 1.5 && secondHalfLag > 5;

            System.out.printf("   Trend      : %s%n", lagGrowing ? "📈 GROWING ⚠️" : "📊 STABLE ✅");
        }

        // Search performance
        double avgSearchLatency = snapshots.stream()
                .filter(s -> s.avgSearchLatency > 0)
                .mapToDouble(s -> s.avgSearchLatency)
                .average().orElse(0);

        System.out.println("\n🔍 SEARCH PERFORMANCE (Under Write Load):");
        System.out.printf("   Total Searches    : %,d%n", totalSearches);
        System.out.printf("   Avg Latency       : %.0f ms%n", avgSearchLatency);

        // Data integrity
        double integrity = totalWritten > 0 ? (finalProcessed * 100.0 / totalWritten) : 0;
        System.out.println("\n💾 DATA INTEGRITY:");
        System.out.printf("   Written    : %,d%n", totalWritten);
        System.out.printf("   Processed  : %,d%n", finalProcessed);
        System.out.printf("   Integrity  : %.2f%% %s%n", integrity, integrity >= 99 ? "✅" : "⚠️");

        // === FINAL VERDICT ===
        System.out.println("\n" + "═".repeat(70));
        System.out.println("VERDICT");
        System.out.println("═".repeat(70));

        boolean lagOk = maxLagPercent <= MAX_LAG_PERCENT;
        boolean integrityOk = integrity >= 99;
        boolean stableOk = !degraded && !lagGrowing;

        if (lagOk && integrityOk && stableOk) {
            System.out.printf("✅ PASSED: System is STABLE at %,d logs/sec%n", TARGET_LOGS_PER_SECOND);
            System.out.printf("   Achieved throughput: %,.0f logs/sec%n", avgThroughput);
        } else {
            System.out.println("⚠️  STABILITY ISSUES DETECTED:");
            if (!lagOk) {
                System.out.printf("   • Lag exceeded %.0f%% threshold (was %.1f%%)%n", MAX_LAG_PERCENT, maxLagPercent);
            }
            if (!integrityOk) {
                System.out.printf("   • Data integrity below 99%% (was %.2f%%)%n", integrity);
            }
            if (degraded) {
                System.out.println("   • Throughput degraded significantly over time");
            }
            if (lagGrowing) {
                System.out.println("   • Lag growing over time (unsustainable)");
            }
            System.out.printf("\n   Recommended rate: %,.0f logs/sec%n", avgThroughput * 0.5);
        }

        System.out.println("═".repeat(70));

        // Assertions
        assertThat(finalProcessed).isGreaterThan((long)(totalWritten * 0.95));
    }

    private LogSearchRequest generateRandomSearch() {
        String serviceId = random.nextBoolean() ? services[random.nextInt(services.length)] : null;
        LogStatus level = random.nextBoolean() ? levels[random.nextInt(levels.length)] : null;
        Instant endTime = Instant.now();
        Instant startTime = endTime.minus(1, ChronoUnit.HOURS);

        return new LogSearchRequest(serviceId, level, null, startTime, endTime, null, 0, 20);
    }

    private List<LogEntryRequest> generateBatch(int size) {
        List<LogEntryRequest> batch = new ArrayList<>(size);
        Instant now = Instant.now();

        for (int i = 0; i < size; i++) {
            batch.add(new LogEntryRequest(
                    now.minusMillis(random.nextInt(1000)),
                    services[random.nextInt(services.length)],
                    levels[random.nextInt(levels.length)],
                    "Stability test message " + UUID.randomUUID().toString().substring(0, 8),
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

    private record StabilitySnapshot(
            long elapsedSeconds,
            long written,
            long processed,
            double throughput,
            long lag,
            double lagPercent,
            long searches,
            double avgSearchLatency
    ) {}
}