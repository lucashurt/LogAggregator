package com.example.logaggregator.kafka.ConsumersAndProducers;

import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class LogProducer {

    private final KafkaTemplate<String, LogEntryRequest> kafkaTemplate;
    private static final String TOPIC = "logs";

    public LogProducer(KafkaTemplate<String, LogEntryRequest> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendLog(LogEntryRequest request) {
        CompletableFuture<SendResult<String, LogEntryRequest>> future =
                kafkaTemplate.send(
                        TOPIC,
                        request.serviceId(),
                        request);

        future.whenComplete((r, e) -> {
            if(e != null) {
                log.error("Failed to send log to Kafka: serviceId={}, traceId={}, error={}",
                        request.serviceId(),
                        request.traceId(),
                        e.getMessage());
            }
        });
    }

    public void sendLogBatch(List<LogEntryRequest> requests) {
        long startTime = System.currentTimeMillis();
        requests.forEach(this::sendLog);
        long duration = System.currentTimeMillis() - startTime;

        log.info("Batch of {} logs queued for sending in {}ms", requests.size(), duration);
    }
}
