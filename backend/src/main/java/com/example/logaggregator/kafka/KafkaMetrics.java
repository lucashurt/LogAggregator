package com.example.logaggregator.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class KafkaMetrics {

    private final Counter logsPublishedCounter;
    private final Counter logsConsumedCounter;
    private final Counter logsFailedCounter;

    private final Timer consumerBatchProcessingTime;
    private final Timer apiResponseTime;

    public KafkaMetrics(MeterRegistry meterRegistry) {
        //producer metrics
        this.logsPublishedCounter = Counter.builder("logs.published.total")
                .description("Total number of logs published to Kafka")
                .register(meterRegistry);

        this.apiResponseTime = Timer.builder("api.logs.ingest.duration")
                .description("Time taken to accept log via API (queue to Kafka")
                .register(meterRegistry);

        //consumer metrics
        this.logsConsumedCounter = Counter.builder("logs.consumed.total")
                .description("Total number of logs consumed and saved to DB")
                .register(meterRegistry);

        this.consumerBatchProcessingTime = Timer.builder("consumer.batch.processing.duration")
                .description("Time taken to process a batch of logs")
                .register(meterRegistry);

        //error metrics
        this.logsFailedCounter = Counter.builder("logs.dlq.total")
                .description("Total number of logs sent to Dead Letter Queue")
                .register(meterRegistry);
    }

    public void recordLogPublished(){
        logsPublishedCounter.increment();
    }

    public void recordLogConsumed(){
        logsConsumedCounter.increment();
    }

    public void recordLogFailed(){
        logsFailedCounter.increment();
    }

    public void recordBatchConsumed(int count){
        logsConsumedCounter.increment(count);
    }

    public void recordBatchPublished(int count){
        logsPublishedCounter.increment(count);
    }

    public void recordApiResponse(long startTimeNanos){
        apiResponseTime.record(System.nanoTime() - startTimeNanos, TimeUnit.NANOSECONDS);
    }

    public void recordConsumerBatchProcessingTime(long startTimeNanos){
        consumerBatchProcessingTime.record(
                System.nanoTime() - startTimeNanos,
                TimeUnit.NANOSECONDS
        );
    }
}
