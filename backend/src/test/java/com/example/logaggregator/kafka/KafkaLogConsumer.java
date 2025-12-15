package com.example.logaggregator.kafka;

import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import com.example.logaggregator.logs.services.LogIngestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class KafkaLogConsumer {

    private final LogIngestService logIngestService;

    public KafkaLogConsumer(LogIngestService logIngestService) {
        this.logIngestService = logIngestService;
    }

    @KafkaListener(topics = "logs", groupId = "log-processor-group")
    public void consumeLog(LogEntryRequest request) {
        try{
            log.info("Received log from Kafka: service: {}, level = {}",
                    request.serviceId(), request.level());
            logIngestService.ingest(request);
        }
        catch (Exception e){
            log.error("Error processing log from Kafka", e);
        }
    }
}
