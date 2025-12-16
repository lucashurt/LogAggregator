package com.example.logaggregator.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class DLQControllerTest {
        private MeterRegistry meterRegistry;
        private DLQController dlqController;

        @BeforeEach
        void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        dlqController = new DLQController(meterRegistry);
    }

        // ==================== Status Endpoint Tests ====================

        @Test
        void shouldReturnDLQStatusWithZeroCount() {
        // When: Get DLQ status with no messages
        Map<String, Object> status = dlqController.getDLQStatus();

        // Then: Should return basic info
        assertThat(status).containsKeys("dlq_topic", "checked_at", "dlq_count", "message");
        assertThat(status.get("dlq_topic")).isEqualTo("logs-dlq");
        assertThat(status.get("dlq_count")).isEqualTo(0.0);
    }

        @Test
        void shouldReturnDLQStatusWithMessages() {
        // Given: 5 messages in DLQ
        Counter dlqCounter = meterRegistry.counter("logs.dlq.total");
        dlqCounter.increment(5);

        // When: Get DLQ status
        Map<String, Object> status = dlqController.getDLQStatus();

        // Then: Should show count
        assertThat(status.get("dlq_count")).isEqualTo(5.0);
    }

        // ==================== Metrics Endpoint Tests ====================

        @Test
        void shouldCalculateDLQRateCorrectly() {
        // Given: 100 published, 98 consumed, 2 in DLQ
        meterRegistry.counter("logs.published.total").increment(100);
        meterRegistry.counter("logs.consumed.total").increment(98);
        meterRegistry.counter("logs.dlq.total").increment(2);

        // When: Get metrics
        Map<String, Object> metrics = dlqController.getDLQMetrics();

        // Then: Should calculate 2/98 = 2.04% error rate
        assertThat(metrics.get("logs_published_total")).isEqualTo(100L);
        assertThat(metrics.get("logs_consumed_total")).isEqualTo(98L);
        assertThat(metrics.get("dlq_messages_total")).isEqualTo(2L);
        assertThat(metrics.get("dlq_rate_percent")).isEqualTo("2.04%");
        assertThat(metrics.get("consumer_lag")).isEqualTo(2L);
    }

        @Test
        void shouldReturnZeroDLQRateWhenNoLogsConsumed() {
        // Given: No logs consumed yet
        // (all counters at 0)

        // When: Get metrics
        Map<String, Object> metrics = dlqController.getDLQMetrics();

        // Then: DLQ rate should be 0% (avoid division by zero)
        assertThat(metrics.get("dlq_rate_percent")).isEqualTo("0.00%");
    }

        @Test
        void shouldMarkHealthyWhenDLQRateIsLow() {
        // Given: 1000 consumed, 5 in DLQ (0.5% error rate)
        meterRegistry.counter("logs.consumed.total").increment(1000);
        meterRegistry.counter("logs.dlq.total").increment(5);

        // When: Get metrics
        Map<String, Object> metrics = dlqController.getDLQMetrics();

        // Then: Should be HEALTHY
        assertThat(metrics.get("health_status")).isEqualTo("HEALTHY");
    }

        @Test
        void shouldWarnWhenDLQRateIsHigh() {
        // Given: 100 consumed, 2 in DLQ (2% error rate - above 1% threshold)
        meterRegistry.counter("logs.consumed.total").increment(100);
        meterRegistry.counter("logs.dlq.total").increment(2);

        // When: Get metrics
        Map<String, Object> metrics = dlqController.getDLQMetrics();

        // Then: Should show warning
        assertThat(metrics.get("health_status")).asString()
                .contains("WARNING");
    }

        @Test
        void shouldWarnWhenConsumerLagIsHigh() {
        // Given: 15000 published, 4000 consumed (11k lag)
        meterRegistry.counter("logs.published.total").increment(15000);
        meterRegistry.counter("logs.consumed.total").increment(4000);

        // When: Get metrics
        Map<String, Object> metrics = dlqController.getDLQMetrics();

        // Then: Should show warning for high lag
        assertThat(metrics.get("consumer_lag")).isEqualTo(11000L);
        assertThat(metrics.get("health_status")).asString()
                .contains("WARNING");
    }

        // ==================== Info Endpoint Tests ====================

        @Test
        void shouldProvideKafkaInspectionInstructions() {
        // When: Get DLQ info
        Map<String, Object> info = dlqController.getDLQInfo();

        // Then: Should include all instructions
        assertThat(info).containsKeys("dlq_topic", "instructions");

        @SuppressWarnings("unchecked")
        Map<String, String> instructions = (Map<String, String>) info.get("instructions");

        assertThat(instructions).containsKeys(
                "check_messages",
                "count_messages",
                "consumer_group",
                "view_metrics_api",
                "view_prometheus_metrics"
        );

        assertThat(instructions.get("check_messages"))
                .contains("kafka-console-consumer");
    }
}
