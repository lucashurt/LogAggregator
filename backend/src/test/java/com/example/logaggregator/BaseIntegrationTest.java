package com.example.logaggregator;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

@SpringBootTest
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    // Singleton containers - start once, reuse for all tests
    private static final PostgreSQLContainer<?> postgresContainer;
    private static final ElasticsearchContainer elasticsearchContainer;
    private static final KafkaContainer kafkaContainer;

    static {
        // Initialize containers ONCE in static block
        postgresContainer = new PostgreSQLContainer<>(
                DockerImageName.parse("postgres:15-alpine")
        )
                .withDatabaseName("logaggregator_test")
                .withUsername("test")
                .withPassword("test");

        elasticsearchContainer = new ElasticsearchContainer(
                DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.11.0")
        )
                .withEnv("xpack.security.enabled", "false")
                .withEnv("discovery.type", "single-node")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                // Ensure the container is listening for the specific HTTP output
                .waitingFor(Wait.forHttp("/")
                        .forPort(9200)
                        .forStatusCode(200)
                        .withStartupTimeout(java.time.Duration.ofSeconds(120)));

        kafkaContainer = new KafkaContainer(
                DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
        )
                // Kraft mode (no Zookeeper) is faster, though cp-kafka 7.5 usually handles this
                .withEnv("KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:9092,BROKER://0.0.0.0:9093")
                // Force a specific wait strategy to avoid the script lock issue
                .waitingFor(Wait.forLogMessage(".*\\[KafkaServer id=\\d+\\] started.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(120)));
        // Start all containers
        postgresContainer.start();
        elasticsearchContainer.start();
        kafkaContainer.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String esUrl = "http://" + elasticsearchContainer.getHost() + ":" + elasticsearchContainer.getMappedPort(9200);

        // PostgreSQL
        registry.add("spring.datasource.url", postgresContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgresContainer::getUsername);
        registry.add("spring.datasource.password", postgresContainer::getPassword);

        // Elasticsearch - Provide BOTH keys to cover all auto-configurations
        registry.add("spring.elasticsearch.uris", () -> esUrl);
        registry.add("spring.data.elasticsearch.client.uris", () -> esUrl);

        // IMPORTANT: Some versions of the new client require this to be explicitly null/empty
        // when security is disabled to avoid 401/400 errors
        registry.add("spring.elasticsearch.username", () -> "");
        registry.add("spring.elasticsearch.password", () -> "");

        // Kafka
        registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
    }
}