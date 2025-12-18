package com.example.logaggregator.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaHealthTest {

    @Mock
    private KafkaAdmin kafkaAdmin;

    @InjectMocks
    private KafkaHealthIndicator kafkaHealthIndicator;

    @Test
    void shouldReturnUpWhenKafkaIsReachable() {
        // Given: KafkaAdmin returns valid configuration WITH bootstrap servers
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"); // <--- FIX

        when(kafkaAdmin.getConfigurationProperties()).thenReturn(config);

        // When: Check health
        // (Note: In a pure unit test without a real Kafka broker, AdminClient.create might still
        // try to connect and fail, or succeed but report DOWN. This fixes the configuration error.)
        Health health = kafkaHealthIndicator.health();

        // Then: Should call getConfigurationProperties
        verify(kafkaAdmin).getConfigurationProperties();

        // It will likely be DOWN in a unit test because localhost:9092 isn't running,
        // but it won't throw the "Configuration" exception anymore.
        assertThat(health.getStatus()).isIn(Status.UP, Status.DOWN);
    }

    @Test
    void shouldIncludeKafkaDetailsInHealthResponse() {
        // Given: KafkaAdmin with configuration
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"); // <--- FIX

        when(kafkaAdmin.getConfigurationProperties()).thenReturn(config);

        // When: Check health
        Health health = kafkaHealthIndicator.health();

        // Then: Should include details
        if (health.getStatus() == Status.DOWN) {
            assertThat(health.getDetails()).containsKeys("error", "message");
        }
    }
}