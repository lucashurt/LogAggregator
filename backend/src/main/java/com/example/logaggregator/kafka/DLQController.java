package com.example.logaggregator.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin/dlq")
public class DLQController {

    private final MeterRegistry meterRegistry;

    public DLQController(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @GetMapping("/status")
    public Map<String, Object> getDLQStatus() {
        log.info("DLQ status check requested");

        Map<String, Object> status = new HashMap<>();
        status.put("dlq_topic", "logs-dlq");
        status.put("checked_at", Instant.now().toString());

        double dlqCount = meterRegistry.counter("logs.dlq.total").count();
        status.put("dlq_count", dlqCount);
        status.put("message", "Use Kafka CLI to check DLQ: kafka-console-consumer --topic logs-dlq");

        return status;
    }

    @GetMapping("/metrics")
    public Map<String, Object> getDLQMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        double dlqTotal = meterRegistry.counter("logs.dlq.total").count();
        double publishedTotal = meterRegistry.counter("logs.published.total").count();
        double consumedTotal = meterRegistry.counter("logs.consumed.total").count();

        metrics.put("dlq_messages_total", (long) dlqTotal);
        metrics.put("logs_published_total", (long) publishedTotal);
        metrics.put("logs_consumed_total", (long) consumedTotal);

        double dlqRate = consumedTotal > 0
                ? (dlqTotal / consumedTotal) * 100
                : 0.0;

        metrics.put("dlq_rate_percent", String.format("%.2f%%", dlqRate));
        long consumerLag = (long) (publishedTotal - consumedTotal);
        metrics.put("consumer_lag", consumerLag);

        metrics.put("timestamp", Instant.now().toString());
        if (dlqRate > 1.0) {
            metrics.put("health_status", "WARNING: DLQ rate above 1%");
        } else if (consumerLag > 10000) {
            metrics.put("health_status", "WARNING: Consumer lag above 10k messages");
        } else {
            metrics.put("health_status", "HEALTHY");
        }
        return metrics;
    }

    @GetMapping("/info")
    public Map<String, Object> getDLQInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("dlq_topic", "logs-dlq");
        info.put("instructions", Map.of(
                "check_messages",
                "kafka-console-consumer --bootstrap-server localhost:9092 --topic logs-dlq --from-beginning",
                "count_messages",
                "kafka-run-class kafka.tools.GetOffsetShell --broker-list localhost:9092 --topic logs-dlq",
                "consumer_group",
                "dlq-inspector",
                "view_metrics_api",
                "GET /api/v1/admin/dlq/metrics",
                "view_prometheus_metrics",
                "GET /actuator/prometheus"
        ));

        return info;
    }
}