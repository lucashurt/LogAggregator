package com.example.logaggregator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.utility.TestcontainersConfiguration;

@SpringBootTest
@ActiveProfiles("test")
class LogAggregatorApplicationTests extends BaseIntegrationTest {

    @Test
    void contextLoads() {
    }

}
