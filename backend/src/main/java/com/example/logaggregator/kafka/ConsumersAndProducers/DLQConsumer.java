package com.example.logaggregator.kafka.ConsumersAndProducers;

import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DLQConsumer {
    @KafkaListener(topics = "logs-dlq", groupId = "dlq-inspector",autoStartup = "false")

    public void inspectDLQMessages(LogEntryRequest request){
        log.warn("DLQ Message Found: serviceId={}, error={}, timestamp={}",
                request.serviceId(),
                request.metadata().get("dlq_error"),
                request.metadata().get("dlq_timestamp"));
    }
}
