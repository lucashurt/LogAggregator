package com.example.logaggregator.kafka.ConsumersAndProducers;

import com.example.logaggregator.kafka.KafkaMetrics;
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
    private final KafkaMetrics kafkaMetrics;
    private static final String TOPIC = "logs";

    public LogProducer(KafkaTemplate<String, LogEntryRequest> kafkaTemplate, KafkaMetrics kafkaMetrics) {
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaMetrics = kafkaMetrics;
    }

    public void sendLog(LogEntryRequest request) {
        long startTime = System.nanoTime();

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
            else{
                kafkaMetrics.recordLogPublished();
                kafkaMetrics.recordApiResponse(startTime);
            }
        });
    }

    public void sendLogBatch(List<LogEntryRequest> requests) {
        requests.forEach(this::sendLog);
    }
}
