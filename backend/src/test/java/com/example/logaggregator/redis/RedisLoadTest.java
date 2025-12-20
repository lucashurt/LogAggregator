package com.example.logaggregator.redis;

import com.example.logaggregator.BaseIntegrationTest;
import com.example.logaggregator.elasticsearch.LogDocument;
import com.example.logaggregator.elasticsearch.LogElasticsearchRepository;
import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import com.example.logaggregator.logs.models.LogStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.IntStream;

@SpringBootTest
@Tag("load-test")
public class RedisLoadTest extends BaseIntegrationTest {

    @Autowired
    private CachedElasticsearchService cachedService;

    @Autowired
    private LogElasticsearchRepository elasticsearchRepository;

    @Autowired
    private CacheManager cacheManager;

    private static final int TOTAL_REQUESTS = 100000;
    private static final int CONCURRENT_USERS = 50;

    @BeforeEach
    void setup() {
        // Clean slate
        elasticsearchRepository.deleteAll();
        cacheManager.getCacheNames().forEach(name ->
                java.util.Objects.requireNonNull(cacheManager.getCache(name)).clear()
        );

        // Seed 100 log documents
        List<LogDocument> docs = IntStream.range(0, 100).mapToObj(i -> {
            LogDocument doc = new LogDocument();
            doc.setId(UUID.randomUUID().toString());
            doc.setServiceId("bench-service");
            doc.setMessage("Performance test log message " + i);
            doc.setLevel(LogStatus.INFO);
            doc.setTimestamp(Instant.now());
            doc.setPostgresId((long) i);
            return doc;
        }).toList();
        elasticsearchRepository.saveAll(docs);
    }

    @Test
    void benchmarkHighConcurrencyAndPercentiles() throws InterruptedException, ExecutionException {
        LogSearchRequest hotRequest = new LogSearchRequest(
                "bench-service", null, null, null, null, null, 0, 50
        );

        // 1. Warmup (Critical for JIT compilation and Redis connection pool initialization)
        System.out.println("Warming up JVM and Redis Pool...");
        for(int i=0; i<100; i++) cachedService.searchWithCache(hotRequest);

        // 2. Prepare Thread Pool
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_USERS);
        List<Callable<Long>> tasks = new ArrayList<>();

        for (int i = 0; i < TOTAL_REQUESTS; i++) {
            tasks.add(() -> {
                long start = System.nanoTime();
                cachedService.searchWithCache(hotRequest);
                return System.nanoTime() - start;
            });
        }

        // 3. Execute Concurrent Load
        System.out.println("Starting Concurrent Load Test (" + CONCURRENT_USERS + " threads)...");
        long wallClockStart = System.currentTimeMillis();
        List<Future<Long>> results = executor.invokeAll(tasks);
        long wallClockDuration = System.currentTimeMillis() - wallClockStart;

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);

        // 4. Calculate Statistics
        List<Long> latencies = new ArrayList<>();
        for (Future<Long> result : results) {
            latencies.add(result.get());
        }
        Collections.sort(latencies);

        double p50 = latencies.get((int) (latencies.size() * 0.50)) / 1_000_000.0;
        double p95 = latencies.get((int) (latencies.size() * 0.95)) / 1_000_000.0;
        double p99 = latencies.get((int) (latencies.size() * 0.99)) / 1_000_000.0;
        double avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        double rps = (double) TOTAL_REQUESTS / (wallClockDuration / 1000.0);

        // 5. Comparison Benchmark (Single Threaded uncached for baseline)
        long startUncached = System.nanoTime();
        cachedService.searchWithoutCache(hotRequest); // run once
        double uncachedLatency = (System.nanoTime() - startUncached) / 1_000_000.0;

        // 6. Report
        System.out.println("\n");
        System.out.println("██████╗ ███████╗██████╗ ██╗███████╗    ██████╗ ███████╗███╗   ██╗ ██████╗██╗  ██╗");
        System.out.println("██╔══██╗██╔════╝██╔══██╗██║██╔════╝    ██╔══██╗██╔════╝████╗  ██║██╔════╝██║  ██║");
        System.out.println("██████╔╝█████╗  ██║  ██║██║███████╗    ██████╔╝█████╗  ██╔██╗ ██║██║     ███████║");
        System.out.println("██╔══██╗██╔══╝  ██║  ██║██║╚════██║    ██╔══██╗██╔══╝  ██║╚██╗██║██║     ██╔══██║");
        System.out.println("██║  ██║███████╗██████╔╝██║███████║    ██████╔╝███████╗██║ ╚████║╚██████╗██║  ██║");
        System.out.println("╚═╝  ╚═╝╚══════╝╚═════╝ ╚═╝╚══════╝    ╚═════╝ ╚══════╝╚═╝  ╚═══╝ ╚═════╝╚═╝  ╚═╝");
        System.out.println("==================================================================================");
        System.out.println(" LOAD TEST RESULTS (Cached)");
        System.out.println("==================================================================================");
        System.out.printf(" Total Requests  : %d%n", TOTAL_REQUESTS);
        System.out.printf(" Concurrent Users: %d%n", CONCURRENT_USERS);
        System.out.printf(" Throughput      : %.2f req/sec%n", rps);
        System.out.println("----------------------------------------------------------------------------------");
        System.out.printf(" Uncached Latency: %.3f ms (Baseline)%n", uncachedLatency);
        System.out.printf(" Cached Avg      : %.3f ms  (Speedup: %.1fx)%n", avg, uncachedLatency/avg);
        System.out.printf(" Cached P50      : %.3f ms%n", p50);
        System.out.printf(" Cached P95      : %.3f ms%n", p95);
        System.out.printf(" Cached P99      : %.3f ms  <-- The \"God Quality\" Metric%n", p99);
        System.out.println("==================================================================================\n");
    }
}