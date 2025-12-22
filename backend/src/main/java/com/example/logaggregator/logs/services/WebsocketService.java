package com.example.logaggregator.logs.services;

import com.example.logaggregator.logs.DTOs.LogEntryResponse;
import com.example.logaggregator.logs.models.LogEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class WebsocketService {
    private final SimpMessagingTemplate messagingTemplate;

    public WebsocketService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcastLog(LogEntry logEntry) {
        try{
            LogEntryResponse response = new LogEntryResponse(
                    logEntry.getId(),
                    logEntry.getTimestamp(),
                    logEntry.getServiceId(),
                    logEntry.getLevel(),
                    logEntry.getMessage(),
                    logEntry.getTraceId(),
                    logEntry.getMetadata(),
                    logEntry.getCreatedAt()
            );
            messagingTemplate.convertAndSend("/topic/logs", response);
            log.debug("Broadcasted log to WebSocket: id={}, serviceId={}",
                    logEntry.getId(), logEntry.getServiceId());
        }
        catch (Exception e){
            log.error("Failed to broadcast log via WebSocket: {}", e.getMessage());

        }
    }
}
