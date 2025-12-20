package com.example.logaggregator.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RedisHealthIndicator implements HealthIndicator {
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisConnectionFactory redisConnectionFactory;

    public RedisHealthIndicator(RedisTemplate<String, Object> redisTemplate, RedisConnectionFactory redisConnectionFactory) {
        this.redisTemplate = redisTemplate;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    @Override
    public Health health() {
        try {
            long startTime = System.nanoTime();

            // Ping Redis
            String pong = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();

            long responseTime = (System.nanoTime() - startTime) / 1_000_000;  // ms

            if ("PONG".equals(pong)) {
                Health.Builder builder = Health.up()
                        .withDetail("response_time_ms", responseTime)
                        .withDetail("status", "Connected");

                // Warn if Redis is slow (> 50ms is concerning)
                if (responseTime > 50) {
                    builder.withDetail("warning", "High latency detected");
                }

                return builder.build();
            } else {
                return Health.down()
                        .withDetail("error", "Unexpected PING response: " + pong)
                        .build();
            }

        } catch (Exception e) {
            log.error("Redis health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("error", e.getClass().getSimpleName())
                    .withDetail("message", e.getMessage())
                    .withDetail("status", "Redis is unreachable")
                    .build();
        }
    }
}

