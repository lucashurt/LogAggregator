package com.example.logaggregator.kafka.ConsumersAndProducers;

import com.example.logaggregator.elasticsearch.services.LogElasticsearchIngestService;
import com.example.logaggregator.kafka.KafkaErrorHandler;
import com.example.logaggregator.kafka.KafkaMetrics;
import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import com.example.logaggregator.logs.models.LogEntry;
import com.example.logaggregator.logs.services.LogIngestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class LogConsumer {

    private final LogElasticsearchIngestService logElasticsearchIngestService;
    private final LogIngestService logIngestService;
    private final KafkaErrorHandler kafkaErrorHandler;
    private final KafkaMetrics kafkaMetrics;

    public LogConsumer(LogIngestService logIngestService, KafkaErrorHandler kafkaErrorHandler,KafkaMetrics kafkaMetrics, LogElasticsearchIngestService logElasticsearchIngestService) {
        this.logElasticsearchIngestService = logElasticsearchIngestService;
        this.logIngestService = logIngestService;
        this.kafkaErrorHandler = kafkaErrorHandler;
        this.kafkaMetrics = kafkaMetrics;
    }

    @KafkaListener(topics = "logs", groupId = "log-processor-group")
    public void consumeLogBatch(
            List<LogEntryRequest> requests,
            @Header(KafkaHeaders.RECEIVED_PARTITION) List<Integer> partitions,
            @Header(KafkaHeaders.OFFSET) List<Long> offsets) {
        long startTime = System.nanoTime();

        log.info("Received batch of {} logs from partition(s) {}",
                requests.size(),
                partitions.stream().distinct().toList());

        try{
            List<LogEntry> savedLogs = logIngestService.ingestBatch(requests);
            logElasticsearchIngestService.indexLogBatch(requests,savedLogs);

            long duration = System.currentTimeMillis() - startTime;
            double throughput = (requests.size() / (duration / 1000.0));

            kafkaMetrics.recordBatchConsumed(requests.size());
            kafkaMetrics.recordConsumerBatchProcessingTime(startTime);

            log.info("Batch processing complete: {} succeeded in {}ms ({} logs/sec)",
                    requests.size(), duration, String.format("%.0f", throughput));
        }
        catch (Exception e){
            log.error("Error while processing logs from partition(s) {}: {}", partitions, e.getMessage());

            for(int i=0; i<requests.size(); i++){
                kafkaErrorHandler.sendToDLQ(
                        requests.get(i),
                        e,
                        partitions.get(i),
                        offsets.get(i)
                );
                kafkaMetrics.recordLogFailed();
            }
        }
    }

}
