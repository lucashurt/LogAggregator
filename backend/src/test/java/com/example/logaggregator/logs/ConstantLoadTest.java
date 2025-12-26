package com.example.logaggregator.logs;

import com.example.logaggregator.BaseIntegrationTest;
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

    // ==================== CONFIGURATION ====================
    // Adjust these to match your expected production load

    private static final int TARGET_LOGS_PER_SECOND = 500;    // Target ingestion rate
    private static final int TEST_DURATION_SECONDS = 30;       // How long to run
    private static final int BATCH_SIZE = 100;                 // Logs per HTTP request
    private static final int MEASUREMENT_INTERVAL_SECONDS = 5; // How often to report metrics

    // Data pools for realistic log generation
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
    }

    /**
     * TEST 1: Measure sustained throughput at a target rate.
     *
     * This simulates your stream_logs.py behavior in a controlled test environment.
     * Sends logs at a steady rate and measures how well the system keeps up.
     */
    @Test
    void measureSustainedThroughput() throws InterruptedException {
        printHeader();

        // Tracking metrics
        AtomicLong totalLogsSent = new AtomicLong(0);
        List<Long> latencies = new CopyOnWriteArrayList<>();
        List<IntervalMetrics> intervalMetrics = new CopyOnWriteArrayList<>();

        // Calculate timing
        int batchesPerSecond = (int) Math.ceil((double) TARGET_LOGS_PER_SECOND / BATCH_SIZE);
        long delayBetweenBatches = 1000 / batchesPerSecond;

        System.out.printf("📊 Configuration:%n");
        System.out.printf("   Target Rate    : %,d logs/sec%n", TARGET_LOGS_PER_SECOND);
        System.out.printf("   Batch Size     : %d logs%n", BATCH_SIZE);
        System.out.printf("   Batches/Second : %d%n", batchesPerSecond);
        System.out.printf("   Test Duration  : %d seconds%n", TEST_DURATION_SECONDS);
        System.out.printf("   Expected Total : %,d logs%n", TARGET_LOGS_PER_SECOND * TEST_DURATION_SECONDS);
        System.out.println("-".repeat(80));

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

        // Track interval metrics
        AtomicLong intervalStart = new AtomicLong(System.currentTimeMillis());
        AtomicLong intervalLogsSent = new AtomicLong(0);

        long testStartTime = System.currentTimeMillis();

        // Schedule batch sends at regular intervals (simulates continuous stream)
        ScheduledFuture<?> sendTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                List<LogEntryRequest> batch = generateBatch(BATCH_SIZE);

                long requestStart = System.nanoTime();
                ResponseEntity<String> response = sendBatch(batch);
                long requestDuration = System.nanoTime() - requestStart;

                if (response.getStatusCode().is2xxSuccessful()) {
                    totalLogsSent.addAndGet(BATCH_SIZE);
                    intervalLogsSent.addAndGet(BATCH_SIZE);
                    latencies.add(requestDuration / 1_000_000); // Convert to ms
                }
            } catch (Exception e) {
                System.err.println("❌ Batch send failed: " + e.getMessage());
            }
        }, 0, delayBetweenBatches, TimeUnit.MILLISECONDS);

        // Schedule interval reporting (shows progress every N seconds)
        ScheduledFuture<?> reportTask = scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            long intervalDuration = now - intervalStart.get();
            long logsInInterval = intervalLogsSent.getAndSet(0);
            intervalStart.set(now);

            double intervalThroughput = (logsInInterval / (intervalDuration / 1000.0));
            long postgresCount = logRepository.count();
            long lag = totalLogsSent.get() - postgresCount;

            IntervalMetrics metrics = new IntervalMetrics(
                    (now - testStartTime) / 1000,
                    logsInInterval,
                    intervalThroughput,
                    postgresCount,
                    lag
            );
            intervalMetrics.add(metrics);

            System.out.printf("⏱️  [%3ds] Sent: %,6d | Throughput: %,.0f/s | DB: %,d | Lag: %,d%n",
                    metrics.elapsedSeconds, metrics.logsSent, metrics.throughput,
                    metrics.dbCount, metrics.lag);

        }, MEASUREMENT_INTERVAL_SECONDS, MEASUREMENT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // Run for specified duration
        Thread.sleep(TEST_DURATION_SECONDS * 1000L);

        // Shutdown
        sendTask.cancel(false);
        reportTask.cancel(false);
        scheduler.shutdown();
        scheduler.awaitTermination(5, TimeUnit.SECONDS);

        // Wait for processing to complete (consumer lag to clear)
        System.out.println("\n⏳ Waiting for consumer lag to clear...");
        long waitStart = System.currentTimeMillis();
        long maxWaitMs = 30000;

        while (System.currentTimeMillis() - waitStart < maxWaitMs) {
            long dbCount = logRepository.count();
            if (dbCount >= totalLogsSent.get() * 0.99) {
                break;
            }
            Thread.sleep(500);
        }

        // Final metrics
        long testEndTime = System.currentTimeMillis();
        long totalDuration = testEndTime - testStartTime;
        long finalDbCount = logRepository.count();

        printResults(totalLogsSent.get(), finalDbCount, totalDuration, latencies, intervalMetrics);

        assertThat(finalDbCount).isGreaterThan(0);
    }

    /**
     * TEST 2: Find the maximum sustainable throughput.
     *
     * Starts at a low rate and escalates until the system can't keep up.
     * This tells you the actual capacity of your system.
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
        System.out.println("Finding the maximum sustainable throughput your system can handle...\n");

        int[] testRates = {100, 250, 500, 1000, 2000, 5000, 10000};
        int testDurationPerRate = 10;

        List<RateTestResult> results = new ArrayList<>();

        for (int rate : testRates) {
            // Clean up between tests
            logRepository.deleteAll();
            elasticsearchRepository.deleteAll();
            Thread.sleep(2000);

            System.out.printf("🧪 Testing %,d logs/sec for %d seconds...%n", rate, testDurationPerRate);

            RateTestResult result = testRate(rate, testDurationPerRate);
            results.add(result);

            System.out.printf("   → Actual: %,.0f/s | Lag: %,d | Success: %s%n",
                    result.actualThroughput, result.finalLag,
                    result.sustainable ? "✅ SUSTAINABLE" : "❌ FALLING BEHIND");

            if (!result.sustainable) {
                System.out.println("   ⚠️  System cannot sustain this rate. Stopping escalation.");
                break;
            }
        }

        // Print summary
        System.out.println("\n" + "=".repeat(100));
        System.out.println("RESULTS SUMMARY");
        System.out.println("=".repeat(100));
        System.out.printf("%-15s | %-15s | %-10s | %-15s | %-10s%n",
                "Target Rate", "Actual Rate", "Lag", "Efficiency", "Status");
        System.out.println("-".repeat(75));

        int maxSustainable = 0;
        for (RateTestResult r : results) {
            double efficiency = (r.actualThroughput / r.targetRate) * 100;
            System.out.printf("%-,15d | %-,15.0f | %-,10d | %13.1f%% | %-10s%n",
                    r.targetRate, r.actualThroughput, r.finalLag, efficiency,
                    r.sustainable ? "✅ OK" : "❌ FAIL");
            if (r.sustainable) {
                maxSustainable = r.targetRate;
            }
        }

        System.out.println("=".repeat(100));
        System.out.printf("🏆 MAXIMUM SUSTAINABLE THROUGHPUT: %,d logs/second%n", maxSustainable);
        System.out.printf("📊 Daily Capacity: ~%,d logs/day%n", maxSustainable * 86400L);
        System.out.println("=".repeat(100));
    }

    /**
     * TEST 3: Stress test beyond capacity.
     *
     * Pushes the system WAY beyond its limits to see how it degrades gracefully.
     * Tests resilience, not performance.
     */
    @Test
    void stressTestBeyondCapacity() throws InterruptedException {
        System.out.println("\n");
        System.out.println("💥 STRESS TEST - Pushing Beyond Capacity");
        System.out.println("=".repeat(80));

        int extremeRate = 20000;
        int stressDuration = 15;

        System.out.printf("🔥 Target: %,d logs/sec for %d seconds%n", extremeRate, stressDuration);
        System.out.printf("   Expected total: %,d logs%n", extremeRate * stressDuration);
        System.out.println("-".repeat(80));

        logRepository.deleteAll();
        elasticsearchRepository.deleteAll();

        AtomicLong totalSent = new AtomicLong(0);
        AtomicLong totalAccepted = new AtomicLong(0);
        AtomicLong totalRejected = new AtomicLong(0);
        List<Long> responseTimes = new CopyOnWriteArrayList<>();

        int batchSize = 200;
        int batchesPerSecond = extremeRate / batchSize;
        long delayMs = 1000 / batchesPerSecond;

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(8);
        long startTime = System.currentTimeMillis();

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
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
        }, 0, delayMs, TimeUnit.MILLISECONDS);

        // Progress reporting
        for (int i = 0; i < stressDuration; i++) {
            Thread.sleep(1000);
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            long dbCount = logRepository.count();
            System.out.printf("   [%2ds] Sent: %,d | In DB: %,d | Rejected: %,d%n",
                    elapsed, totalSent.get(), dbCount, totalRejected.get());
        }

        task.cancel(false);
        scheduler.shutdown();
        scheduler.awaitTermination(5, TimeUnit.SECONDS);

        // Wait for processing
        Thread.sleep(5000);

        long finalDbCount = logRepository.count();
        long duration = System.currentTimeMillis() - startTime;

        // Calculate percentiles
        Collections.sort(responseTimes);
        double p50 = responseTimes.isEmpty() ? 0 : responseTimes.get(responseTimes.size() / 2);
        double p95 = responseTimes.isEmpty() ? 0 : responseTimes.get((int)(responseTimes.size() * 0.95));
        double p99 = responseTimes.isEmpty() ? 0 : responseTimes.get((int)(responseTimes.size() * 0.99));

        System.out.println("\n" + "=".repeat(80));
        System.out.println("STRESS TEST RESULTS");
        System.out.println("=".repeat(80));
        System.out.printf("   Logs Sent (API)     : %,d%n", totalSent.get());
        System.out.printf("   Logs Accepted       : %,d%n", totalAccepted.get());
        System.out.printf("   Logs Rejected       : %,d%n", totalRejected.get());
        System.out.printf("   Logs in DB          : %,d%n", finalDbCount);
        System.out.printf("   Processing Rate     : %,.0f logs/sec%n", finalDbCount / (duration / 1000.0));
        System.out.printf("   Data Loss           : %.2f%%%n",
                totalAccepted.get() > 0 ? 100.0 * (totalAccepted.get() - finalDbCount) / totalAccepted.get() : 0);
        System.out.println("\n   Response Time Percentiles:");
        System.out.printf("   P50: %.0fms | P95: %.0fms | P99: %.0fms%n", p50, p95, p99);
        System.out.println("=".repeat(80));
    }

    // ==================== HELPER METHODS ====================

    private RateTestResult testRate(int targetRate, int durationSeconds) throws InterruptedException {
        AtomicLong totalSent = new AtomicLong(0);
        int batchSize = Math.min(100, targetRate);
        int batchesPerSecond = (int) Math.ceil((double) targetRate / batchSize);
        long delayMs = 1000 / Math.max(1, batchesPerSecond);

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
        long startTime = System.currentTimeMillis();

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                List<LogEntryRequest> batch = generateBatch(batchSize);
                ResponseEntity<String> response = sendBatch(batch);
                if (response.getStatusCode().is2xxSuccessful()) {
                    totalSent.addAndGet(batchSize);
                }
            } catch (Exception ignored) {}
        }, 0, delayMs, TimeUnit.MILLISECONDS);

        Thread.sleep(durationSeconds * 1000L);
        task.cancel(false);
        scheduler.shutdown();
        scheduler.awaitTermination(2, TimeUnit.SECONDS);

        // Wait for processing
        Thread.sleep(3000);

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        long dbCount = logRepository.count();
        long lag = totalSent.get() - dbCount;
        double actualThroughput = dbCount / (duration / 1000.0);

        // Sustainable if lag is less than 10% of total sent
        boolean sustainable = lag < (totalSent.get() * 0.1);

        return new RateTestResult(targetRate, actualThroughput, lag, sustainable);
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
        System.out.println("              THROUGHPUT TEST - Simulating Real-World Load             ");
        System.out.println("=".repeat(80));
    }

    private void printResults(long totalSent, long dbCount, long duration,
                              List<Long> latencies, List<IntervalMetrics> intervals) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("FINAL RESULTS");
        System.out.println("=".repeat(80));

        double actualThroughput = dbCount / (duration / 1000.0);
        double efficiency = totalSent > 0 ? ((double) dbCount / totalSent) * 100 : 0;

        System.out.printf("%-30s : %,d%n", "Logs Sent (API Accepted)", totalSent);
        System.out.printf("%-30s : %,d%n", "Logs Persisted (PostgreSQL)", dbCount);
        System.out.printf("%-30s : %,d ms%n", "Total Test Duration", duration);
        System.out.printf("%-30s : %,.2f logs/sec%n", "Actual Throughput", actualThroughput);
        System.out.printf("%-30s : %.1f%%%n", "Processing Efficiency", efficiency);

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
            double minThroughput = intervals.stream()
                    .mapToDouble(i -> i.throughput)
                    .min()
                    .orElse(0);
            double maxThroughput = intervals.stream()
                    .mapToDouble(i -> i.throughput)
                    .max()
                    .orElse(0);

            System.out.printf("   Average : %,.0f logs/sec%n", avgThroughput);
            System.out.printf("   Min     : %,.0f logs/sec%n", minThroughput);
            System.out.printf("   Max     : %,.0f logs/sec%n", maxThroughput);

            // Check for degradation
            if (intervals.size() >= 4) {
                double firstHalf = intervals.subList(0, intervals.size() / 2).stream()
                        .mapToDouble(i -> i.throughput).average().orElse(0);
                double secondHalf = intervals.subList(intervals.size() / 2, intervals.size()).stream()
                        .mapToDouble(i -> i.throughput).average().orElse(0);

                if (secondHalf < firstHalf * 0.9) {
                    System.out.println("   ⚠️  WARNING: Throughput degraded over time (possible resource exhaustion)");
                } else {
                    System.out.println("   ✅ Throughput remained stable throughout test");
                }
            }

            // Consumer lag analysis
            long maxLag = intervals.stream().mapToLong(i -> i.lag).max().orElse(0);
            long finalLag = intervals.get(intervals.size() - 1).lag;

            System.out.println("\n📉 Consumer Lag Analysis:");
            System.out.printf("   Max Lag During Test : %,d logs%n", maxLag);
            System.out.printf("   Final Lag           : %,d logs%n", finalLag);

            if (maxLag > totalSent * 0.2) {
                System.out.println("   ⚠️  WARNING: High consumer lag detected. System falling behind.");
            } else if (maxLag > totalSent * 0.1) {
                System.out.println("   ⚡ NOTICE: Moderate lag. System is catching up.");
            } else {
                System.out.println("   ✅ Consumer keeping pace with ingestion.");
            }
        }

        System.out.println("\n" + "=".repeat(80));
        System.out.printf("🎯 RECOMMENDATION: Based on these results, your system can sustainably handle%n");
        System.out.printf("   approximately %,.0f logs/second in a production environment.%n", actualThroughput * 0.8);
        System.out.println("=".repeat(80));
    }

    // ==================== INNER CLASSES ====================

    private record IntervalMetrics(long elapsedSeconds, long logsSent, double throughput,
                                   long dbCount, long lag) {}

    private record RateTestResult(int targetRate, double actualThroughput,
                                  long finalLag, boolean sustainable) {}
}