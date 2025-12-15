package com.example.logaggregator.kafka;

import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import com.example.logaggregator.logs.services.LogIngestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class KafkaLogConsumer {

    private final LogIngestService logIngestService;

    public KafkaLogConsumer(LogIngestService logIngestService) {
        this.logIngestService = logIngestService;
    }

    @KafkaListener(topics = "logs", groupId = "log-processor-group")
    public void consumeLogBatch(
            List<LogEntryRequest> requests,
            @Header(KafkaHeaders.RECEIVED_PARTITION) List<Integer> partitions,
            @Header(KafkaHeaders.OFFSET) List<Integer> offsets) {
        long startTime = System.currentTimeMillis();
        log.info("Received batch of {} logs from partition(s) {}",
                requests.size(),
                partitions.stream().distinct().toList());

        int successfulLogs = 0;
        int failedLogs = 0;
        for (int i=0; i<requests.size(); i++) {
            LogEntryRequest request = requests.get(i);
            try{
                logIngestService.ingest(request);
                successfulLogs++;
            }
            catch (Exception e){
                failedLogs++;
                log.error("Failed to process log [partition={}, offset={}]: serviceId={}, traceId={}, error={}",
                        partitions.get(i),
                        offsets.get(i),
                        request.serviceId(),
                        request.traceId(),
                        e.getMessage());
            }
        }
        long duration = System.currentTimeMillis() - startTime;
        double throughput = (successfulLogs / (duration / 1000.0));

        log.info("Batch processing complete: {} succeeded, {} failed in {}ms ({} logs/sec)",
                successfulLogs, failedLogs, duration, String.format("%.0f", throughput));

    }

}
