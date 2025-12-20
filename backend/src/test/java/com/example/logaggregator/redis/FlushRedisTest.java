package com.example.logaggregator.redis;

import com.example.logaggregator.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@SpringBootTest
public class FlushRedisTest extends BaseIntegrationTest {

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Test
    void flushRedis() {
        connectionFactory.getConnection().flushAll();
        System.out.println(">>> REDIS FLUSHED SUCCESSFULLY <<<");
    }
}