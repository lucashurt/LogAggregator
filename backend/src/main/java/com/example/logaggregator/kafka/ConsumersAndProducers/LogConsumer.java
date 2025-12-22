package com.example.logaggregator.kafka.ConsumersAndProducers;

import com.example.logaggregator.elasticsearch.services.LogElasticsearchIngestService;
import com.example.logaggregator.kafka.KafkaErrorHandler;
import com.example.logaggregator.kafka.KafkaMetrics;
import com.example.logaggregator.logs.DTOs.LogEntryRequest;
import com.example.logaggregator.logs.models.LogEntry;
import com.example.logaggregator.logs.services.LogIngestService;
import com.example.logaggregator.logs.services.WebsocketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture; // Import this

@Slf4j
@Service
public class LogConsumer {

    private final LogElasticsearchIngestService logElasticsearchIngestService;
    private final LogIngestService logIngestService;
    private final KafkaErrorHandler kafkaErrorHandler;
    private final KafkaMetrics kafkaMetrics;
    private final WebsocketService  websocketService;

    public LogConsumer(LogIngestService logIngestService, KafkaErrorHandler kafkaErrorHandler,KafkaMetrics kafkaMetrics, LogElasticsearchIngestService logElasticsearchIngestService, WebsocketService websocketService) {
        this.logElasticsearchIngestService = logElasticsearchIngestService;
        this.logIngestService = logIngestService;
        this.kafkaErrorHandler = kafkaErrorHandler;
        this.kafkaMetrics = kafkaMetrics;
        this.websocketService = websocketService;
    }

    @KafkaListener(topics = "logs", groupId = "log-processor-group")
    public void consumeLogBatch(
            List<LogEntryRequest> requests,
            @Header(KafkaHeaders.RECEIVED_PARTITION) List<Integer> partitions,
            @Header(KafkaHeaders.OFFSET) List<Long> offsets) {
        long startTime = System.nanoTime();

        log.debug("Received batch of {} logs from partition(s) {}",
                requests.size(),
                partitions.stream().distinct().toList());

        try{
            // 1. Critical Path: Write to PostgreSQL (We wait for this ensures data safety)
            List<LogEntry> savedLogs = logIngestService.ingestBatch(requests);

            // 2. Non-Critical Path: Index to Elasticsearch (Async / Fire-and-Forget)
            // This unblocks the Kafka consumer immediately.
            CompletableFuture.runAsync(() -> {
                try {
                    logElasticsearchIngestService.indexLogBatch(requests, savedLogs);
                } catch (Exception e) {
                    // Log error but don't stop the pipeline
                    log.error("Async Elasticsearch indexing failed: {}", e.getMessage());
                }
            });

            // Metrics
            long duration = System.currentTimeMillis() - (startTime / 1_000_000); // convert nano to milli
            double throughput = (requests.size() / (duration / 1000.0));

            CompletableFuture.runAsync(() -> {
                savedLogs.forEach(websocketService :: broadcastLog);
            });

            kafkaMetrics.recordBatchConsumed(requests.size());
            kafkaMetrics.recordConsumerBatchProcessingTime(startTime);

            log.info("Batch processing complete: {} logs saved to Postgres (ES async) in {}ms",
                    requests.size(), duration);
        }
        catch (Exception e){
            log.error("Error while processing logs from partition(s) {}: {}", partitions, e.getMessage());

            // If Postgres fails, we send to DLQ
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
