package com.example.logaggregator.redis;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tracks Redis cache performance metrics.
 *
 * CRITICAL FIX: This now properly tracks cache hits/misses using Spring's
 * Cache abstraction rather than just creating unused counters.
 *
 * METRICS TRACKED:
 * - cache.hits.total: Successful cache retrievals
 * - cache.misses.total: Cache misses (triggered Elasticsearch query)
 * - cache.errors.total: Cache operation failures
 *
 * BUSINESS VALUE:
 * - Calculate cache hit ratio (hits / (hits + misses))
 * - Identify if cache TTL is too short (high miss rate)
 * - Detect Redis connectivity issues (high error rate)
 */
@Slf4j
@Component
public class RedisCacheMetrics implements CacheErrorHandler {

    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;
    private final Counter cacheErrorCounter;

    public RedisCacheMetrics(MeterRegistry meterRegistry) {
        this.cacheHitCounter = Counter.builder("cache.hits.total")
                .description("Total number of cache hits")
                .tag("cache", "log-searches")
                .register(meterRegistry);

        this.cacheMissCounter = Counter.builder("cache.misses.total")
                .description("Total number of cache misses")
                .tag("cache", "log-searches")
                .register(meterRegistry);

        this.cacheErrorCounter = Counter.builder("cache.errors.total")
                .description("Total number of cache operation errors")
                .tag("cache", "log-searches")
                .register(meterRegistry);
    }

    /**
     * Called by Spring when cache retrieval succeeds.
     */
    public void recordHit() {
        cacheHitCounter.increment();
    }

    /**
     * Called by Spring when cache retrieval misses.
     */
    public void recordMiss() {
        cacheMissCounter.increment();
    }

    /**
     * Log cache statistics every 5 minutes.
     * Helps identify if cache is effective.
     */
    @Scheduled(fixedDelay = 300000)  // 5 minutes
    public void logCacheStats() {
        double hits = cacheHitCounter.count();
        double misses = cacheMissCounter.count();
        double errors = cacheErrorCounter.count();
        double total = hits + misses;

        if (total > 0) {
            double hitRatio = (hits / total) * 100;
            log.info("Cache Statistics - Hit Ratio: {:.2f}% ({} hits / {} total requests, {} errors)",
                    hitRatio, (long)hits, (long)total, (long)errors);

            if (hitRatio < 20) {
                log.warn("Low cache hit ratio detected! Consider increasing TTL or reviewing query patterns.");
            }
        }
    }

    // ==================== CacheErrorHandler Implementation ====================

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        cacheErrorCounter.increment();
        log.error("Cache GET error for key={} in cache={}: {}",
                key, cache.getName(), exception.getMessage());
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, @Nullable Object value) {
        cacheErrorCounter.increment();
        log.error("Cache PUT error for key={} in cache={}: {}",
                key, cache.getName(), exception.getMessage());
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        cacheErrorCounter.increment();
        log.error("Cache EVICT error for key={} in cache={}: {}",
                key, cache.getName(), exception.getMessage());
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        cacheErrorCounter.increment();
        log.error("Cache CLEAR error for cache={}: {}",
                cache.getName(), exception.getMessage());
    }
}