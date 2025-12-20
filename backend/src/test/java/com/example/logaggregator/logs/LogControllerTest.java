package com.example.logaggregator.logs;

import com.example.logaggregator.elasticsearch.services.LogElasticsearchSearchService;
import com.example.logaggregator.kafka.ConsumersAndProducers.LogProducer;
import com.example.logaggregator.logs.services.LogPostgresSearchService;
import com.example.logaggregator.redis.CachedElasticsearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@WebMvcTest(LogController.class)
@AutoConfigureMockMvc(addFilters = false)
public class LogControllerTest {
    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    private LogProducer logProducer;

    @MockitoBean
    private LogPostgresSearchService logPostgresSearchService;

    @MockitoBean
    private CachedElasticsearchService cachedElasticsearchService;

    @Test
    void shouldIngestValidLog() throws Exception {
        doNothing().when(logProducer).sendLog(any());

        mockMvc.perform(post("/api/v1/logs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                            {
                                "timestamp" : "2025-01-01T00:00:00Z",
                                "serviceId" : "auth-service",
                                "level" : "INFO",
                                "message" : "User logged in",
                                "metadata" : {"ip": "127.0.0.1"},
                                "traceId" : "trace-123"
                            }
                        """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.Status").value("Log accepted for processing"));
        verify(logProducer, times(1)).sendLog(any());
    }

    @Test
    void shouldRejectLogWithNoTimestamp() throws Exception {
        mockMvc.perform(post("/api/v1/logs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                            {
                                "serviceId" : "auth-service",
                                "level" : "INFO",
                                "message" : "User logged in",
                                "metadata" : {"ip": "127.0.0.1"},
                                "traceId" : "trace-123"
                            }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").value("timestamp is required"));
    }

    @Test
    void shouldRejectLogWithNoLevel() throws Exception {
        mockMvc.perform(post("/api/v1/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "timestamp" : "2025-01-01T00:00:00Z",
                                "serviceId" : "auth-service",
                                "message" : "User logged in",
                                "metadata" : {"ip": "127.0.0.1"},
                                "traceId" : "trace-123"
                            }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.level").value("Log Level is required"));
    }

    @Test
    void shouldRejectLogWithNoServiceId() throws Exception {
        mockMvc.perform(post("/api/v1/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "timestamp" : "2025-01-01T00:00:00Z",
                                "level" : "INFO",
                                "message" : "User logged in",
                                "metadata" : {"ip": "127.0.0.1"},
                                "traceId" : "trace-123"
                            }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.serviceId").value("Service ID is required"));
    }

    @Test
    void shouldIngestValidLogBatch() throws Exception {
        doNothing().when(logProducer).sendLog(any());

        mockMvc.perform(post("/api/v1/logs/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                            [
                                {
                                    "timestamp" : "2025-01-01T00:00:00Z",
                                    "serviceId" : "auth-service",
                                    "level" : "INFO",
                                    "message" : "User logged in",
                                    "metadata" : {"ip": "127.0.0.1"},
                                    "traceId" : "trace-123"
                                },
                                {
                                    "timestamp" : "2025-01-01T01:00:00Z",
                                    "serviceId" : "payment-service",
                                    "level" : "INFO",
                                    "message" : "Payment processed",
                                    "metadata" : {"amount": "100"},
                                    "traceId" : "trace-456"
                               }
                            ]
                        """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("2 logs accepted for processing"));
        verify(logProducer, times(1)).sendLogBatch(anyList());

    }

    @Test
    void shouldRejectValidLogBatchWithInvalidData() throws Exception {
        mockMvc.perform(post("/api/v1/logs/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                [
                    {
                        "timestamp" : "2025-01-01T00:00:00Z",
                                "level" : "INFO",
                                "message" : "User logged in",
                                "metadata" : {"ip": "127.0.0.1"},
                                "traceId" : "trace-123"
                    },
                    {
                        "timestamp" : "2025-01-01T00:00:00Z",
                                "serviceId" : "auth-service",
                                "metadata" : {"ip": "127.0.0.1"},
                                "traceId" : "trace-123"
                    }
                ]
                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldCallProducerWhenLogIsIngested() throws Exception {
        // Given: Mock producer
        doNothing().when(logProducer).sendLog(any());

        // When: POST a log
        mockMvc.perform(post("/api/v1/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {
                        "timestamp" : "2025-01-01T00:00:00Z",
                        "serviceId" : "test-service",
                        "level" : "INFO",
                        "message" : "Test producer call",
                        "metadata" : {}
                    }
                """))
                .andExpect(status().isAccepted());

        // Then: Verify producer was called
        verify(logProducer, times(1)).sendLog(any());
    }
}
