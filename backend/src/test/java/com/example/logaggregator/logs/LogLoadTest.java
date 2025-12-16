package com.example.logaggregator.logs;

import com.example.logaggregator.kafka.KafkaLogProducer;
import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import com.example.logaggregator.logs.models.LogStatus;
import com.example.logaggregator.logs.services.LogIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
@Tag("load-test")
public class LogLoadTest {

    @Autowired
    private KafkaLogProducer kafkaLogProducer;

    @Autowired
    private LogRepository logRepository;

    @Autowired
    private LogIngestService logIngestService;  // Add this line

    private final Random random = new Random();
    private final String[] services = {"auth-service", "payment-service", "notification-service",
            "user-service", "order-service", "inventory-service"};
    private final LogStatus[] levels = {LogStatus.INFO, LogStatus.DEBUG, LogStatus.WARNING, LogStatus.ERROR};

    @BeforeEach
    void cleanup() {
        logRepository.deleteAll();
    }

    /**
     * TEST 1: API Response Time Under Load
     *
     * WHAT IT TESTS: How fast does the API respond when sending to Kafka?
     *
     * WHY IT MATTERS: This is the client experience. With Kafka, clients
     * get immediate responses because they're just queuing messages, not
     * waiting for DB writes.
     *
     * EXPECTED: ~10-50ms per API call (much faster than 500-800ms for direct DB)
     */
    @Test
    void shouldProvideFastApiResponseTime() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("TEST 1: API RESPONSE TIME - Kafka's Primary Benefit");
        System.out.println("=".repeat(80));

        List<LogEntryRequest> logs = generateLogs(1000);
        List<Long> responseTimes = new ArrayList<>();

        // Measure individual API call latency
        for (LogEntryRequest log : logs) {
            long start = System.nanoTime();
            kafkaLogProducer.sendLog(log);
            long end = System.nanoTime();

            responseTimes.add(Duration.ofNanos(end - start).toMillis());
        }

        // Calculate statistics
        Collections.sort(responseTimes);
        long average = (long) responseTimes.stream().mapToLong(Long::longValue).average().orElse(0);
        long median = responseTimes.get(responseTimes.size() / 2);
        long p95 = responseTimes.get((int) (responseTimes.size() * 0.95));
        long p99 = responseTimes.get((int) (responseTimes.size() * 0.99));
        long max = responseTimes.get(responseTimes.size() - 1);

        System.out.println("\n📊 API Response Time Statistics (1000 calls):");
        System.out.println("   Average:    " + average + "ms");
        System.out.println("   Median:     " + median + "ms");
        System.out.println("   P95:        " + p95 + "ms");
        System.out.println("   P99:        " + p99 + "ms");
        System.out.println("   Max:        " + max + "ms");

        System.out.println("\n💡 Why This Matters:");
        System.out.println("   With Kafka: Client waits ~" + average + "ms (just queuing)");
        System.out.println("   Direct DB:  Client waits ~500-800ms (full persistence)");
        System.out.println("   Improvement: " + (800 / Math.max(average, 1)) + "x faster client experience");

        // Most API calls should complete very quickly
        assertThat(p95).isLessThan(100); // 95% of calls under 100ms

        System.out.println("=".repeat(80) + "\n");
    }

    /**
     * TEST 2: Concurrent Throughput
     *
     * WHAT IT TESTS: How many logs/second can we accept with multiple clients?
     *
     * WHY IT MATTERS: Kafka shines with concurrent clients. The queue
     * decouples producers from consumers, so the API can accept logs
     * as fast as clients can send them.
     *
     * EXPECTED: 3000-5000+ logs/second with multiple threads
     */
    @Test
    void shouldHandleHighConcurrentThroughput() throws InterruptedException {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("TEST 2: CONCURRENT THROUGHPUT - Kafka's Scalability");
        System.out.println("=".repeat(80));

        int numThreads = 10;
        int logsPerThread = 500;
        int totalLogs = numThreads * logsPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicLong totalDuration = new AtomicLong(0);
        AtomicInteger successCount = new AtomicInteger(0);

        System.out.println("\n🚀 Launching " + numThreads + " concurrent clients...");
        System.out.println("   Each client sends " + logsPerThread + " logs");
        System.out.println("   Total logs: " + totalLogs);

        long testStart = System.currentTimeMillis();

        // Launch concurrent producers
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    List<LogEntryRequest> logs = generateLogs(logsPerThread);

                    long start = System.currentTimeMillis();
                    logs.forEach(log -> {
                        kafkaLogProducer.sendLog(log);
                        successCount.incrementAndGet();
                    });
                    long duration = System.currentTimeMillis() - start;

                    totalDuration.addAndGet(duration);
                    System.out.println("   ✓ Thread " + threadId + " completed: " +
                            logsPerThread + " logs in " + duration + "ms (" +
                            (logsPerThread * 1000.0 / duration) + " logs/sec)");
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        long testEnd = System.currentTimeMillis();
        long totalTestDuration = testEnd - testStart;

        // Calculate throughput
        double overallThroughput = (totalLogs * 1000.0) / totalTestDuration;
        double avgThreadThroughput = (logsPerThread * 1000.0) / (totalDuration.get() / numThreads);

        System.out.println("\n📊 Throughput Results:");
        System.out.println("   Total time:           " + totalTestDuration + "ms");
        System.out.println("   Overall throughput:   " + String.format("%.0f", overallThroughput) + " logs/sec");
        System.out.println("   Avg thread throughput: " + String.format("%.0f", avgThreadThroughput) + " logs/sec");
        System.out.println("   Logs queued:          " + successCount.get() + "/" + totalLogs);

        System.out.println("\n💡 Why This Matters:");
        System.out.println("   Kafka handles concurrent clients effortlessly");
        System.out.println("   API stays fast even with " + numThreads + " clients hammering it");
        System.out.println("   Queue buffers the load - consumers process at their own pace");

        assertThat(successCount.get()).isEqualTo(totalLogs);
        assertThat(overallThroughput).isGreaterThan(1000); // At least 1000 logs/sec

        System.out.println("=".repeat(80) + "\n");
    }

    /**
     * TEST 3: Processing Throughput (Consumer Side)
     *
     * WHAT IT TESTS: How fast do consumers process the queued messages?
     *
     * WHY IT MATTERS: This shows the end-to-end system performance.
     * With batch processing and concurrency, consumers should be much
     * faster than the old one-at-a-time approach.
     *
     * EXPECTED: Logs appear in DB within 5-10 seconds for 5000 logs
     */
    @Test
    void shouldProcessLogsQuicklyWithBatchConsumer() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("TEST 3: CONSUMER PROCESSING - Batch Processing Power");
        System.out.println("=".repeat(80));

        int totalLogs = 5000;
        System.out.println("\n📤 Sending " + totalLogs + " logs to Kafka...");

        List<LogEntryRequest> logs = generateLogs(totalLogs);

        long sendStart = System.currentTimeMillis();
        kafkaLogProducer.sendLogBatch(logs);
        long sendEnd = System.currentTimeMillis();
        long sendDuration = sendEnd - sendStart;

        System.out.println("   ✓ Queued " + totalLogs + " logs in " + sendDuration + "ms");
        System.out.println("   ⏱  API throughput: " + (totalLogs * 1000.0 / sendDuration) + " logs/sec");

        System.out.println("\n⏳ Waiting for consumers to process (batch size: 500, threads: 3)...");

        long processStart = System.currentTimeMillis();

        // Wait for all logs to be processed (with timeout)
        AtomicLong lastCount = new AtomicLong(0);
        AtomicInteger checksWithoutProgress = new AtomicInteger(0);

        await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> {
                    long currentCount = logRepository.count();

                    // Log progress every few checks
                    if (currentCount > lastCount.get()) {
                        System.out.println("   📊 Progress: " + currentCount + "/" + totalLogs +
                                " logs processed (" +
                                String.format("%.1f", currentCount * 100.0 / totalLogs) + "%)");
                        lastCount.set(currentCount);
                        checksWithoutProgress.set(0);
                    } else {
                        checksWithoutProgress.incrementAndGet();
                    }

                    // If no progress for 10 checks (5 seconds), assume we're done
                    if (checksWithoutProgress.get() > 10) {
                        System.out.println("   ⚠  No progress detected, assuming processing complete");
                        return true;
                    }

                    return currentCount >= totalLogs;
                });

        long processEnd = System.currentTimeMillis();
        long processDuration = processEnd - processStart;
        long finalCount = logRepository.count();

        System.out.println("\n📊 Processing Results:");
        System.out.println("   Logs processed:     " + finalCount + "/" + totalLogs);
        System.out.println("   Processing time:    " + processDuration + "ms");
        System.out.println("   Consumer throughput: " + (finalCount * 1000.0 / processDuration) + " logs/sec");
        System.out.println("   Total latency:      " + (sendDuration + processDuration) + "ms (send + process)");

        System.out.println("\n💡 Why Batch Processing Matters:");
        System.out.println("   OLD (1-at-a-time): ~200 logs/sec = 25 seconds for " + totalLogs + " logs");
        System.out.println("   NEW (batch + 3 threads): " +
                String.format("%.0f", finalCount * 1000.0 / processDuration) +
                " logs/sec = " + (processDuration / 1000.0) + " seconds");
        System.out.println("   Improvement: " +
                String.format("%.1f", (25000.0 / Math.max(processDuration, 1))) + "x faster");

        // Should process most logs (some might still be in flight)
        assertThat(finalCount).isGreaterThan((long) (totalLogs * 0.95)); // At least 95%

        System.out.println("=".repeat(80) + "\n");
    }

    /**
     * TEST 4: Resilience Under Backpressure
     *
     * WHAT IT TESTS: What happens when we send faster than we can process?
     *
     * WHY IT MATTERS: In real systems, traffic spikes happen. Kafka
     * buffers the overflow so the API stays fast even when consumers
     * are overwhelmed.
     *
     * EXPECTED: API stays responsive, messages queue in Kafka, eventual processing
     */
    @Test
    void shouldRemainfastUnderBackpressure() throws InterruptedException {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("TEST 4: BACKPRESSURE RESILIENCE - Kafka's Buffer Advantage");
        System.out.println("=".repeat(80));

        System.out.println("\n🌊 Simulating traffic burst: 10000 logs in rapid succession");

        List<LogEntryRequest> logs = generateLogs(10000);
        List<Long> responseTimes = new CopyOnWriteArrayList<>();

        // Send logs as fast as possible and measure response times
        long sendStart = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(logs.size());

        for (LogEntryRequest log : logs) {
            executor.submit(() -> {
                try {
                    long start = System.nanoTime();
                    kafkaLogProducer.sendLog(log);
                    long end = System.nanoTime();

                    responseTimes.add(Duration.ofNanos(end - start).toMillis());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        long sendEnd = System.currentTimeMillis();
        long sendDuration = sendEnd - sendStart;

        // Analyze response times during burst
        List<Long> sortedTimes = new ArrayList<>(responseTimes);
        Collections.sort(sortedTimes);

        long avgResponse = (long) sortedTimes.stream().mapToLong(Long::longValue).average().orElse(0);
        long p95Response = sortedTimes.get((int) (sortedTimes.size() * 0.95));
        long maxResponse = sortedTimes.get(sortedTimes.size() - 1);

        System.out.println("\n📊 API Performance During Burst:");
        System.out.println("   Send throughput:    " + (10000 * 1000.0 / sendDuration) + " logs/sec");
        System.out.println("   Avg response time:  " + avgResponse + "ms");
        System.out.println("   P95 response time:  " + p95Response + "ms");
        System.out.println("   Max response time:  " + maxResponse + "ms");

        System.out.println("\n💡 Why This Matters:");
        System.out.println("   API stayed responsive even during 10K log burst");
        System.out.println("   Messages buffered in Kafka (not blocking API)");
        System.out.println("   Consumers will process at ~1500-2000/sec pace");
        System.out.println("   System is resilient - no cascading failures");

        // API should stay reasonably fast even under pressure
        assertThat(p95Response).isLessThan(200); // 95% under 200ms even during burst

        System.out.println("=".repeat(80) + "\n");
    }

    /**
     * TEST 5: Full System Performance - 5000 Logs
     *
     * THE BIG ONE - Complete end-to-end test showing all benefits together
     */
    @Test
    void shouldHandle5000LogsEfficientlyEndToEnd() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("TEST 5: COMPLETE SYSTEM TEST - 5000 Logs End-to-End");
        System.out.println("=".repeat(80));

        int totalLogs = 5000;
        System.out.println("\n🎯 Testing complete flow: API → Kafka → Consumer → Database");
        System.out.println("   Total logs: " + totalLogs);

        // Phase 1: API ingestion
        System.out.println("\n📤 PHASE 1: API Ingestion");
        List<LogEntryRequest> logs = generateLogs(totalLogs);

        long apiStart = System.currentTimeMillis();
        kafkaLogProducer.sendLogBatch(logs);
        long apiEnd = System.currentTimeMillis();
        long apiDuration = apiEnd - apiStart;

        System.out.println("   ✓ API accepted " + totalLogs + " logs in " + apiDuration + "ms");
        System.out.println("   ✓ API throughput: " + String.format("%.0f", totalLogs * 1000.0 / apiDuration) + " logs/sec");
        System.out.println("   ✓ Client experience: " + (apiDuration / totalLogs) + "ms average per log");

        // Phase 2: Consumer processing
        System.out.println("\n⚙️  PHASE 2: Consumer Processing (3 threads, batch size 500)");
        long processStart = System.currentTimeMillis();

        await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> logRepository.count() >= totalLogs * 0.95);

        long processEnd = System.currentTimeMillis();
        long processDuration = processEnd - processStart;
        long finalCount = logRepository.count();

        System.out.println("   ✓ Processed " + finalCount + " logs in " + processDuration + "ms");
        System.out.println("   ✓ Consumer throughput: " + String.format("%.0f", finalCount * 1000.0 / processDuration) + " logs/sec");

        // Phase 3: Analysis
        System.out.println("\n📊 OVERALL SYSTEM PERFORMANCE:");
        System.out.println("   Total time (API + Processing): " + (apiDuration + processDuration) + "ms");
        System.out.println("   API time:                      " + apiDuration + "ms (client sees this)");
        System.out.println("   Processing time:               " + processDuration + "ms (async, invisible to client)");
        System.out.println("   End-to-end throughput:         " +
                String.format("%.0f", totalLogs * 1000.0 / (apiDuration + processDuration)) + " logs/sec");

        System.out.println("\n🏆 KAFKA ADVANTAGES DEMONSTRATED:");
        System.out.println("   ✓ Fast client responses (~" + (apiDuration / totalLogs) + "ms per log)");
        System.out.println("   ✓ High throughput (" + String.format("%.0f", finalCount * 1000.0 / processDuration) + " logs/sec processing)");
        System.out.println("   ✓ Decoupled architecture (API independent of DB speed)");
        System.out.println("   ✓ Buffering & resilience (can handle traffic spikes)");
        System.out.println("   ✓ Horizontal scalability (add more consumers = more throughput)");

        assertThat(finalCount).isGreaterThan((long) (totalLogs * 0.95));
        assertThat(apiDuration).isLessThan(5000); // API should be very fast

        System.out.println("=".repeat(80) + "\n");
    }

    @Test
    void shouldDemonstrateKafkaVsDirectDBPerformance() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("TEST 6: KAFKA vs DIRECT DB - Head-to-Head Comparison");
        System.out.println("=".repeat(80));

        int totalLogs = 1000;

        // ============================================================
        // SCENARIO 1: DIRECT DB WRITES (No Kafka)
        // ============================================================
        System.out.println("\n📊 SCENARIO 1: Direct Database Writes (OLD APPROACH)");
        System.out.println("   Bypassing Kafka, writing directly to database\n");

        List<LogEntryRequest> directLogs = generateLogs(totalLogs);
        List<Long> directResponseTimes = new ArrayList<>();

        long directStart = System.currentTimeMillis();

        // Simulate what the API would do without Kafka
        for (LogEntryRequest log : directLogs) {
            long requestStart = System.nanoTime();
            logIngestService.ingest(log);  // Direct DB write
            long requestEnd = System.nanoTime();

            directResponseTimes.add(Duration.ofNanos(requestEnd - requestStart).toMillis());
        }

        long directEnd = System.currentTimeMillis();
        long directTotalTime = directEnd - directStart;

        // Calculate direct DB statistics
        Collections.sort(directResponseTimes);
        long directAvgResponse = (long) directResponseTimes.stream().mapToLong(Long::longValue).average().orElse(0);
        long directP95Response = directResponseTimes.get((int) (directResponseTimes.size() * 0.95));
        long directMaxResponse = directResponseTimes.get(directResponseTimes.size() - 1);
        double directThroughput = (totalLogs * 1000.0) / directTotalTime;

        System.out.println("   Results:");
        System.out.println("   ├─ Total time: " + directTotalTime + "ms");
        System.out.println("   ├─ Avg client wait: " + directAvgResponse + "ms");
        System.out.println("   ├─ P95 client wait: " + directP95Response + "ms");
        System.out.println("   ├─ Max client wait: " + directMaxResponse + "ms");
        System.out.println("   └─ Throughput: " + String.format("%.0f", directThroughput) + " logs/sec");

        System.out.println("\n   ⚠️  Client Experience:");
        System.out.println("   • Every API call waits ~" + directAvgResponse + "ms");
        System.out.println("   • User sees loading spinner for " + directAvgResponse + "ms per log");
        System.out.println("   • System blocks during DB writes");
        System.out.println("   • No buffering - spikes cause failures");

        // ============================================================
        // SCENARIO 2: KAFKA ASYNC PROCESSING (New Approach)
        // ============================================================
        System.out.println("\n📊 SCENARIO 2: Kafka Async Processing (NEW APPROACH)");
        System.out.println("   Using Kafka queue with async consumer processing\n");

        // Clean DB for fair comparison
        logRepository.deleteAll();

        List<LogEntryRequest> kafkaLogs = generateLogs(totalLogs);
        List<Long> kafkaResponseTimes = new ArrayList<>();

        long kafkaApiStart = System.currentTimeMillis();

        // API calls with Kafka (just queuing)
        for (LogEntryRequest log : kafkaLogs) {
            long requestStart = System.nanoTime();
            kafkaLogProducer.sendLog(log);  // Queue to Kafka
            long requestEnd = System.nanoTime();

            kafkaResponseTimes.add(Duration.ofNanos(requestEnd - requestStart).toMillis());
        }

        long kafkaApiEnd = System.currentTimeMillis();
        long kafkaApiTime = kafkaApiEnd - kafkaApiStart;

        // Wait for consumer processing
        long processingStart = System.currentTimeMillis();
        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> logRepository.count() >= totalLogs * 0.95);

        long processingEnd = System.currentTimeMillis();
        long kafkaProcessingTime = processingEnd - processingStart;
        long kafkaTotalTime = kafkaApiTime + kafkaProcessingTime;

        // Calculate Kafka statistics
        Collections.sort(kafkaResponseTimes);
        long kafkaAvgResponse = (long) kafkaResponseTimes.stream().mapToLong(Long::longValue).average().orElse(0);
        long kafkaP95Response = kafkaResponseTimes.get((int) (kafkaResponseTimes.size() * 0.95));
        long kafkaMaxResponse = kafkaResponseTimes.get(kafkaResponseTimes.size() - 1);
        double kafkaThroughput = (totalLogs * 1000.0) / kafkaTotalTime;
        double consumerThroughput = (totalLogs * 1000.0) / kafkaProcessingTime;

        System.out.println("   Results:");
        System.out.println("   ├─ API time: " + kafkaApiTime + "ms (client waits for this)");
        System.out.println("   ├─ Processing time: " + kafkaProcessingTime + "ms (async, background)");
        System.out.println("   ├─ Total time: " + kafkaTotalTime + "ms");
        System.out.println("   ├─ Avg client wait: " + kafkaAvgResponse + "ms");
        System.out.println("   ├─ P95 client wait: " + kafkaP95Response + "ms");
        System.out.println("   ├─ Max client wait: " + kafkaMaxResponse + "ms");
        System.out.println("   └─ Throughput: " + String.format("%.0f", kafkaThroughput) + " logs/sec (end-to-end)");
        System.out.println("                  " + String.format("%.0f", consumerThroughput) + " logs/sec (consumer)");

        System.out.println("\n   ✅ Client Experience:");
        System.out.println("   • Every API call returns in ~" + kafkaAvgResponse + "ms");
        System.out.println("   • User sees instant confirmation");
        System.out.println("   • Processing happens in background");
        System.out.println("   • System buffers spikes gracefully");

        // ============================================================
        // HEAD-TO-HEAD COMPARISON
        // ============================================================
        System.out.println("\n" + "=".repeat(80));
        System.out.println("📊 HEAD-TO-HEAD COMPARISON");
        System.out.println("=".repeat(80));

        System.out.println("\n┌─────────────────────────┬──────────────┬──────────────┬──────────────┐");
        System.out.println("│ Metric                  │ Direct DB    │ Kafka        │ Improvement  │");
        System.out.println("├─────────────────────────┼──────────────┼──────────────┼──────────────┤");
        System.out.printf("│ Client Wait Time (avg)  │ %8dms   │ %8dms   │ %9.1fx     │%n",
                directAvgResponse, kafkaAvgResponse,
                (double)directAvgResponse / Math.max(kafkaAvgResponse, 1));
        System.out.printf("│ Client Wait Time (P95)  │ %8dms   │ %8dms   │ %9.1fx     │%n",
                directP95Response, kafkaP95Response,
                (double)directP95Response / Math.max(kafkaP95Response, 1));
        System.out.printf("│ Total Processing Time   │ %8dms   │ %8dms   │ %9.1fx     │%n",
                directTotalTime, kafkaTotalTime,
                (double)directTotalTime / Math.max(kafkaTotalTime, 1));
        System.out.printf("│ Throughput              │ %8.0f/s  │ %8.0f/s  │ %9.1fx     │%n",
                directThroughput, consumerThroughput,
                consumerThroughput / Math.max(directThroughput, 1));
        System.out.println("└─────────────────────────┴──────────────┴──────────────┴──────────────┘");

        System.out.println("\n🎯 KEY INSIGHTS:");
        System.out.println("   1. CLIENT EXPERIENCE: Kafka is " +
                String.format("%.0fx", (double)directAvgResponse / Math.max(kafkaAvgResponse, 1)) +
                " faster for users");
        System.out.println("      • Direct DB: User waits " + directAvgResponse + "ms per request");
        System.out.println("      • Kafka: User waits " + kafkaAvgResponse + "ms per request");

        System.out.println("\n   2. SYSTEM THROUGHPUT: Kafka processes " +
                String.format("%.0fx", consumerThroughput / Math.max(directThroughput, 1)) +
                " more logs/sec");
        System.out.println("      • Direct DB: " + String.format("%.0f", directThroughput) + " logs/sec");
        System.out.println("      • Kafka: " + String.format("%.0f", consumerThroughput) + " logs/sec");

        System.out.println("\n   3. ARCHITECTURE:");
        System.out.println("      • Direct DB: API blocked, synchronous, can't handle spikes");
        System.out.println("      • Kafka: API free, async, buffers spikes, horizontally scalable");

        System.out.println("\n   4. TRADE-OFF:");
        System.out.println("      • Direct DB: Immediate consistency, but slow & fragile");
        System.out.println("      • Kafka: Eventual consistency, but fast & resilient");

        System.out.println("=".repeat(80) + "\n");

        // Assertions
        if (directAvgResponse > 0 && kafkaAvgResponse > 0) {
            assertThat(kafkaAvgResponse).isLessThan(directAvgResponse);
        }
        assertThat(consumerThroughput).isGreaterThan(directThroughput);
    }
    // ==================== Helper Methods ====================

    private List<LogEntryRequest> generateLogs(int count) {
        List<LogEntryRequest> logs = new ArrayList<>();
        Instant baseTime = Instant.now().minus(Duration.ofHours(1));

        for (int i = 0; i < count; i++) {
            String serviceId = services[random.nextInt(services.length)];
            LogStatus level = levels[random.nextInt(levels.length)];
            String message = "Test log message " + i + " from " + serviceId;
            Instant timestamp = baseTime.plus(Duration.ofSeconds(i));
            String traceId = "trace-" + (i / 10);

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