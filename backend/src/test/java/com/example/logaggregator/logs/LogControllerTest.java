package com.example.logaggregator.logs;

import com.example.logaggregator.logs.models.LogEntry;
import com.example.logaggregator.logs.models.LogStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@WebMvcTest(LogController.class)
@AutoConfigureMockMvc(addFilters = false)
public class LogControllerTest {
    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    private LogService logService;

    @Test
    void shouldIngestValidLog() throws Exception {
        LogEntry logEntry = new LogEntry();
        logEntry.setId(1L);
        logEntry.setTimestamp(Instant.parse("2025-01-01T00:00:00Z"));
        logEntry.setServiceId("auth-service");
        logEntry.setMessage("User logged in");
        logEntry.setTraceId("trace-123");
        logEntry.setMetadata(Map.of("ip","127.0.0.1"));
        logEntry.setLevel(LogStatus.INFO);
        logEntry.setCreatedAt(Instant.now());

        when(logService.ingest(any())).thenReturn(logEntry);
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
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.serviceId").value("auth-service"))
                .andExpect(jsonPath("$.level").value("INFO"))
                .andExpect(jsonPath("$.message").value("User logged in"))
                .andExpect(jsonPath("$.traceId").value("trace-123"));
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
        LogEntry logEntry1 = new LogEntry();
        logEntry1.setId(1L);
        logEntry1.setTimestamp(Instant.parse("2025-01-01T00:00:00Z"));
        logEntry1.setServiceId("auth-service");
        logEntry1.setMessage("User logged in");
        logEntry1.setTraceId("trace-123");
        logEntry1.setMetadata(Map.of("ip","127.0.0.1"));
        logEntry1.setLevel(LogStatus.INFO);
        logEntry1.setCreatedAt(Instant.now());

        LogEntry logEntry2 = new LogEntry();
        logEntry2.setId(2L);
        logEntry2.setTimestamp(Instant.parse("2025-01-01T00:00:00Z"));
        logEntry2.setServiceId("payment-service");
        logEntry2.setMessage("Payment processed");
        logEntry2.setTraceId("trace-456");
        logEntry2.setMetadata(Map.of("amount","100"));
        logEntry2.setLevel(LogStatus.INFO);
        logEntry2.setCreatedAt(Instant.now());

        List<LogEntry> logEntries = new ArrayList<>();
        logEntries.add(logEntry1);
        logEntries.add(logEntry2);
        when(logService.ingestBatch(any())).thenReturn(logEntries);

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
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2))

                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].serviceId").value("auth-service"))
                .andExpect(jsonPath("$[0].level").value("INFO"))
                .andExpect(jsonPath("$[0].message").value("User logged in"))
                .andExpect(jsonPath("$[0].traceId").value("trace-123"))

                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].serviceId").value("payment-service"))
                .andExpect(jsonPath("$[1].level").value("INFO"))
                .andExpect(jsonPath("$[1].message").value("Payment processed"))
                .andExpect(jsonPath("$[1].traceId").value("trace-456"));
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
}
