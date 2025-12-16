package com.example.logaggregator.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class KafkaHealthIndicator implements HealthIndicator {

    private final KafkaAdmin kafkaAdmin;
    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    public Health health(){
        try(AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())){
            var result = adminClient.describeCluster(
                    new DescribeClusterOptions().timeoutMs(500)
            );

            String clusterId = result.clusterId().get(5, TimeUnit.SECONDS);
            int nodeCount = result.nodes().get(5,TimeUnit.SECONDS).size();

            log.debug("Kafka health check successful: clusterId={}, nodes={}", clusterId, nodeCount);

            return Health.up()
                    .withDetail("clusterId", clusterId)
                    .withDetail("node", nodeCount)
                    .withDetail("status", "UP")
                    .build();
        }
        catch(Exception e){
            log.error("Kafka health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("error", e.getClass().getSimpleName())
                    .withDetail("message", e.getMessage())
                    .withDetail("status", "Kafka cluster is unreachable")
                    .build();

        }
    }
}
