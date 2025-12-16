package com.example.logaggregator.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for KafkaMetrics component.
 *
 * WHAT THIS TESTS:
 * - Counters increment correctly when recording methods are called
 * - Timers record durations accurately
 * - Batch operations count correctly
 *
 * WHY IT MATTERS:
 * - Broken metrics = blind in production
 * - These are your operational visibility
 */
class KafkaMetricsTest {

    private MeterRegistry meterRegistry;
    private KafkaMetrics kafkaMetrics;

    @BeforeEach
    void setUp() {
        // Use SimpleMeterRegistry for testing (in-memory)
        meterRegistry = new SimpleMeterRegistry();
        kafkaMetrics = new KafkaMetrics(meterRegistry);
    }

    // ==================== Published Counter Tests ====================

    @Test
    void shouldIncrementPublishedCounterWhenLogIsPublished() {
        // When: Record one published log
        kafkaMetrics.recordLogPublished();

        // Then: Counter should be 1
        Counter counter = meterRegistry.counter("logs.published.total");
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void shouldIncrementPublishedCounterMultipleTimes() {
        // When: Record 5 published logs
        for (int i = 0; i < 5; i++) {
            kafkaMetrics.recordLogPublished();
        }

        // Then: Counter should be 5
        Counter counter = meterRegistry.counter("logs.published.total");
        assertThat(counter.count()).isEqualTo(5.0);
    }

    @Test
    void shouldIncrementPublishedCounterByBatchSize() {
        // When: Record a batch of 100 logs
        kafkaMetrics.recordBatchPublished(100);

        // Then: Counter should be 100
        Counter counter = meterRegistry.counter("logs.published.total");
        assertThat(counter.count()).isEqualTo(100.0);
    }

    // ==================== Consumed Counter Tests ====================

    @Test
    void shouldIncrementConsumedCounterWhenLogIsConsumed() {
        // When: Record one consumed log
        kafkaMetrics.recordLogConsumed();

        // Then: Counter should be 1
        Counter counter = meterRegistry.counter("logs.consumed.total");
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void shouldIncrementConsumedCounterByBatchSize() {
        // When: Record a batch of 50 consumed logs
        kafkaMetrics.recordBatchConsumed(50);

        // Then: Counter should be 50
        Counter counter = meterRegistry.counter("logs.consumed.total");
        assertThat(counter.count()).isEqualTo(50.0);
    }

    // ==================== DLQ Counter Tests ====================

    @Test
    void shouldIncrementDLQCounterWhenLogFails() {
        // When: Record 3 failed logs
        kafkaMetrics.recordLogFailed();
        kafkaMetrics.recordLogFailed();
        kafkaMetrics.recordLogFailed();

        // Then: Counter should be 3
        Counter counter = meterRegistry.counter("logs.dlq.total");
        assertThat(counter.count()).isEqualTo(3.0);
    }

    // ==================== Timer Tests ====================

    @Test
    void shouldRecordApiResponseTime() {
        // Given: A start time (1 second ago in nanoseconds)
        long startTime = System.nanoTime() - 1_000_000_000L; // 1 second ago

        // When: Record API response time
        kafkaMetrics.recordApiResponse(startTime);

        // Then: Timer should have recorded approximately 1 second
        Timer timer = meterRegistry.timer("api.logs.ingest.duration");
        assertThat(timer.count()).isEqualTo(1);
        // Duration should be approximately 1 second (allow some tolerance)
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS))
                .isGreaterThan(0.9)
                .isLessThan(1.1);
    }

    @Test
    void shouldRecordConsumerBatchProcessingTime() {
        // Given: A start time (500ms ago)
        long startTime = System.currentTimeMillis() - 500;

        // When: Record batch processing time
        // Note: Need to convert to nanos for the actual method
        long startTimeNanos = System.nanoTime() - 500_000_000L;
        kafkaMetrics.recordConsumerBatchProcessingTime(startTimeNanos);

        // Then: Timer should have recorded approximately 500ms
        Timer timer = meterRegistry.timer("consumer.batch.processing.duration");
        assertThat(timer.count()).isEqualTo(1);
    }

    // ==================== Integration Scenarios ====================

    @Test
    void shouldTrackCompletePublishConsumeFlow() {
        // Simulate: Publish 100 logs, consume 98, 2 go to DLQ

        // Published
        kafkaMetrics.recordBatchPublished(100);

        // Consumed successfully
        kafkaMetrics.recordBatchConsumed(98);

        // Failed
        kafkaMetrics.recordLogFailed();
        kafkaMetrics.recordLogFailed();

        // Then: Verify all counters
        assertThat(meterRegistry.counter("logs.published.total").count()).isEqualTo(100.0);
        assertThat(meterRegistry.counter("logs.consumed.total").count()).isEqualTo(98.0);
        assertThat(meterRegistry.counter("logs.dlq.total").count()).isEqualTo(2.0);

        // Calculate consumer lag
        double lag = meterRegistry.counter("logs.published.total").count()
                - meterRegistry.counter("logs.consumed.total").count();
        assertThat(lag).isEqualTo(2.0); // 2 logs in DLQ = lag
    }

    @Test
    void shouldAllowMultipleTimerRecordings() {
        // When: Record multiple API calls with different durations
        long fastCall = System.nanoTime() - 10_000_000L;   // 10ms
        long slowCall = System.nanoTime() - 100_000_000L;  // 100ms

        kafkaMetrics.recordApiResponse(fastCall);
        kafkaMetrics.recordApiResponse(slowCall);

        // Then: Timer should show 2 recordings
        Timer timer = meterRegistry.timer("api.logs.ingest.duration");
        assertThat(timer.count()).isEqualTo(2);
    }
}