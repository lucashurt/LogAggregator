package com.example.logaggregator.redis;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RedisCacheMetrics {
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;
    private final Counter cacheEvictionCounter;

    public RedisCacheMetrics(MeterRegistry meterRegistry) {
        this.cacheHitCounter = Counter.builder("cache.hits.total")
                .description("Total number of cache hits")
                .tag("cache", "log-searches")
                .register(meterRegistry);

        this.cacheMissCounter = Counter.builder("cache.misses.total")
                .description("Total number of cache misses")
                .tag("cache", "log-searches")
                .register(meterRegistry);

        this.cacheEvictionCounter = Counter.builder("cache.evictions.total")
                .description("Total number of cache evictions")
                .tag("cache", "log-searches")
                .register(meterRegistry);
    }

    public void recordHit() {
        cacheHitCounter.increment();
    }

    public void recordMiss() {
        cacheMissCounter.increment();
    }

    public void recordEviction() {
        cacheEvictionCounter.increment();
    }

    /**
     * Log cache statistics every 5 minutes.
     * Helps identify if cache is effective.
     */
    @Scheduled(fixedDelay = 300000)  // 5 minutes
    public void logCacheStats() {
        double hits = cacheHitCounter.count();
        double misses = cacheMissCounter.count();
        double total = hits + misses;

        if (total > 0) {
            double hitRatio = (hits / total) * 100;
            log.info("Cache Statistics - Hit Ratio: {:.2f}% ({} hits / {} total requests)",
                    hitRatio, (long)hits, (long)total);

            if (hitRatio < 20) {
                log.warn("Low cache hit ratio detected! Consider increasing TTL or reviewing query patterns.");
            }
        }
    }
}