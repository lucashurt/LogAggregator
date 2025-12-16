package com.example.logaggregator.kafka;

import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class KafkaErrorHandler {
    private final KafkaTemplate<String, LogEntryRequest> kafkaTemplate;
    private static final String DLQ_TOPIC = "logs-dlq";

    public KafkaErrorHandler(KafkaTemplate<String, LogEntryRequest> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendToDLQ(
            LogEntryRequest request,
            Exception error,
            int partition,
            long offset) {

        log.error("Sending to DLQ: serviceId={}, partition={}, offset={}, error={}",
                request.serviceId(), partition, offset, error.getMessage());

        Map<String, Object> metadata = new HashMap<>(
                request.metadata() != null ? request.metadata() : new HashMap<>()
        );

        metadata.put("dlq-timestamp", Instant.now().toString());
        metadata.put("dlq-error", error.getMessage());
        metadata.put("dlq-error-code", error.getClass().getName());
        metadata.put("dlq-original-parrition", partition);
        metadata.put("dlq-original-offset", offset);

        LogEntryRequest enrichedRequest = new LogEntryRequest(
                request.timestamp(),
                request.serviceId(),
                request.level(),
                request.message(),
                metadata,
                request.traceId()
        );

        try{
            kafkaTemplate.send(DLQ_TOPIC, request.serviceId(), enrichedRequest)
                    .whenComplete((result,ex) -> {
                        if(ex != null) {
                            log.error("CRITICAL: Failed to send to DLQ! Data may be lost. " +
                                    "serviceId={}, error={}", request.serviceId(), ex.getMessage());
                        }
                    });
        }
        catch (Exception e){
            log.error("CRITICAL: DLQ send threw exception! serviceId={}, error={}",
                    request.serviceId(), e.getMessage());
        }

    }
}

