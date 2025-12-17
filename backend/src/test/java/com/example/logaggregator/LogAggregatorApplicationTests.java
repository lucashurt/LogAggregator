package com.example.logaggregator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.utility.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)  // ← Add this line
class LogAggregatorApplicationTests {

    @Test
    void contextLoads() {
    }

}
