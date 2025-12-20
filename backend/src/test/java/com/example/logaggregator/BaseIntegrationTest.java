package com.example.logaggregator;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

@SpringBootTest
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    // Singleton containers with @ServiceConnection for auto-configuration
    private static final PostgreSQLContainer<?> postgresContainer;
    private static final ElasticsearchContainer elasticsearchContainer;
    private static final KafkaContainer kafkaContainer;
    private static final GenericContainer<?> redisContainer;

    static {
        // PostgreSQL container
        postgresContainer = new PostgreSQLContainer<>(
                DockerImageName.parse("postgres:15-alpine")
        )
                .withDatabaseName("logaggregator_test")
                .withUsername("test")
                .withPassword("test")
                .withReuse(true);

        // Elasticsearch container - CRITICAL: Disable security for test
        elasticsearchContainer = new ElasticsearchContainer(
                DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.1.0")
        )
                .withEnv("xpack.security.enabled", "false")
                .withEnv("xpack.security.http.ssl.enabled", "false")
                .withEnv("discovery.type", "single-node")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                .waitingFor(Wait.forHttp("/")
                        .forPort(9200)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(120)))
                .withReuse(true);

        // Kafka container
        kafkaContainer = new KafkaContainer(
                DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
        )
                .withReuse(true);

        // Redis
        redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
                .withExposedPorts(6379)
                .withReuse(true);
        // Start all containers
        postgresContainer.start();
        elasticsearchContainer.start();
        kafkaContainer.start();
        redisContainer.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url", postgresContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgresContainer::getUsername);
        registry.add("spring.datasource.password", postgresContainer::getPassword);

        // Elasticsearch - Spring Boot 4.0.0 format
        String esUrl = "http://" + elasticsearchContainer.getHost() + ":"
                + elasticsearchContainer.getMappedPort(9200);

        registry.add("spring.elasticsearch.uris", () -> esUrl);

        // Kafka
        registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);

        //Redis
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
    }
}