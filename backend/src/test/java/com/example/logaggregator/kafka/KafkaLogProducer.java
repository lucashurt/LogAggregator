package com.example.logaggregator.kafka;

import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class KafkaLogProducer {

    private final KafkaTemplate<String, LogEntryRequest> kafkaTemplate;
    private static final String TOPIC = "logs";

    public KafkaLogProducer(KafkaTemplate<String, LogEntryRequest> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendLog(LogEntryRequest request) {
        CompletableFuture<SendResult<String, LogEntryRequest>> future =
                kafkaTemplate.send(TOPIC, request);

        future.whenComplete((r, e) -> {
            if (e == null) {
                log.info("Sent log to Kafka: offset={}, partition={}",
                        r.getRecordMetadata().offset(),
                        r.getRecordMetadata().partition());
            }
            else{
                log.error("Failed to send log to Kafka", e);
            }
        });
    }

    public void sendLogBatch(List<LogEntryRequest> requests) {
        requests.forEach(this::sendLog);
    }
}
