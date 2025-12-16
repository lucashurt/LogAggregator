package com.example.logaggregator.kafka;

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

    /**
     * GET /api/v1/admin/dlq/status
     * Just returns basic DLQ info
     */
    @GetMapping("/status")
    public Map<String, Object> getDLQStatus() {
        log.info("DLQ status check requested");

        Map<String, Object> status = new HashMap<>();
        status.put("dlq_topic", "logs-dlq");
        status.put("message", "Use Kafka CLI to check DLQ: kafka-console-consumer --topic logs-dlq");
        status.put("checked_at", Instant.now().toString());

        return status;
    }

    /**
     * GET /api/v1/admin/dlq/info
     * Returns instructions on how to inspect DLQ
     */
    @GetMapping("/info")
    public Map<String, Object> getDLQInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("dlq_topic", "logs-dlq");
        info.put("instructions", Map.of(
                "check_messages", "kafka-console-consumer --bootstrap-server localhost:9092 --topic logs-dlq --from-beginning",
                "count_messages", "kafka-run-class kafka.tools.GetOffsetShell --broker-list localhost:9092 --topic logs-dlq",
                "consumer_group", "dlq-inspector"
        ));

        return info;
    }
}