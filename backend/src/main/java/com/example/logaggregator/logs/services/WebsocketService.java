package com.example.logaggregator.logs.services;

import com.example.logaggregator.logs.DTOs.LogEntryResponse;
import com.example.logaggregator.logs.models.LogEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class WebsocketService {

    private final SimpMessagingTemplate messagingTemplate;

    // Configurable batch threshold
    private static final int BATCH_THRESHOLD = 10;

    public WebsocketService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * RECOMMENDED: Broadcast a batch of logs as a single message.
     * Use this for high-throughput ingestion.
     *
     * @param logEntries List of log entries to broadcast
     */
    public void broadcastBatch(List<LogEntry> logEntries) {
        if (logEntries == null || logEntries.isEmpty()) {
            return;
        }

        try {
            List<LogEntryResponse> responses = logEntries.stream()
                    .map(this::toResponse)
                    .toList();

            // Send entire batch as single message
            messagingTemplate.convertAndSend("/topic/logs-batch", responses);

            log.debug("Broadcasted batch of {} logs via WebSocket", responses.size());

        } catch (Exception e) {
            log.error("Failed to broadcast log batch via WebSocket: {}", e.getMessage());
        }
    }

    /**
     * Smart broadcast: Uses batch for large lists, individual for small.
     * Provides best of both worlds.
     */
    public void broadcastSmart(List<LogEntry> logEntries) {
        if (logEntries == null || logEntries.isEmpty()) {
            return;
        }

        if (logEntries.size() >= BATCH_THRESHOLD) {
            // Batch mode for efficiency
            broadcastBatch(logEntries);
        } else {
            // Individual mode for low latency on small batches
            logEntries.forEach(this::broadcastLog);
        }
    }

    /**
     * Original method - kept for backward compatibility.
     * Use sparingly - prefer broadcastBatch() for high throughput.
     */
    public void broadcastLog(LogEntry logEntry) {
        try {
            LogEntryResponse response = toResponse(logEntry);
            messagingTemplate.convertAndSend("/topic/logs", response);
            log.debug("Broadcasted log to WebSocket: id={}, serviceId={}",
                    logEntry.getId(), logEntry.getServiceId());
        } catch (Exception e) {
            log.error("Failed to broadcast log via WebSocket: {}", e.getMessage());
        }
    }

    private LogEntryResponse toResponse(LogEntry logEntry) {
        return new LogEntryResponse(
                logEntry.getId(),
                logEntry.getTimestamp(),
                logEntry.getServiceId(),
                logEntry.getLevel(),
                logEntry.getMessage(),
                logEntry.getTraceId(),
                logEntry.getMetadata(),
                logEntry.getCreatedAt()
        );
    }
}