package com.example.logaggregator;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfig {
    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                .withDatabaseName("logaggregator_test")
                .withUsername("test")
                .withPassword("test")
                .withReuse(true); // Reuse container across test runs for speed
    }

    @Bean
    @ServiceConnection
    public ElasticsearchContainer elasticsearchContainer() {
        return new ElasticsearchContainer(DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.8.0"))
                .withEnv("xpack.security.enabled", "false")
                .withEnv("discovery.type", "single-node")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withReuse(true);
    }

    @Bean
    public KafkaContainer kafkaContainer() {
        KafkaContainer container = new KafkaContainer(
                DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
        )
                .withReuse(true);

        container.start();
        return container;
    }

    // Manually configure Kafka connection (no @ServiceConnection)
    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry,
                                org.springframework.context.ApplicationContext context) {
        KafkaContainer kafka = context.getBean(KafkaContainer.class);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
}
