package com.example.logaggregator.logs.services;

import com.example.logaggregator.logs.DTOs.LogEntryResponse;
import com.example.logaggregator.logs.models.LogEntry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class WebsocketService {

    private final SimpMessagingTemplate messagingTemplate;

    // ==================== THROTTLING CONFIGURATION ====================

    /** Minimum time between broadcasts (250ms = max 4 updates/second) */
    private static final long BROADCAST_INTERVAL_MS = 250;

    /** Maximum logs per broadcast (prevents huge JSON payloads) */
    private static final int MAX_LOGS_PER_BROADCAST = 250;

    /** Maximum queue size before dropping oldest logs */
    private static final int MAX_QUEUE_SIZE = 2000;

    /** Threshold for switching to batch-only mode (individual logs disabled) */
    private static final int BATCH_THRESHOLD = 10;

    // ==================== STATE ====================

    private final Queue<LogEntryResponse> pendingLogs = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean liveUpdatesEnabled = new AtomicBoolean(true);
    private final AtomicLong totalLogsQueued = new AtomicLong(0);
    private final AtomicLong totalLogsDropped = new AtomicLong(0);
    private final AtomicLong totalLogsBroadcast = new AtomicLong(0);

    private ScheduledExecutorService scheduler;

    public WebsocketService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "websocket-broadcaster");
            t.setDaemon(true);
            return t;
        });

        // Periodic flush of pending logs
        scheduler.scheduleAtFixedRate(
                this::flushPendingLogs,
                BROADCAST_INTERVAL_MS,
                BROADCAST_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        log.info("WebSocket throttling initialized: {}ms interval, max {} logs/broadcast",
                BROADCAST_INTERVAL_MS, MAX_LOGS_PER_BROADCAST);
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==================== PUBLIC API ====================

    /**
     * RECOMMENDED: Queue logs for throttled broadcast.
     * Use this for high-throughput ingestion.
     *
     * @param logEntries List of log entries to broadcast
     */
    public void broadcastBatch(List<LogEntry> logEntries) {
        if (!liveUpdatesEnabled.get()) {
            return;
        }

        if (logEntries == null || logEntries.isEmpty()) {
            return;
        }

        int added = 0;
        for (LogEntry entry : logEntries) {
            // Backpressure: drop oldest if queue is full
            while (pendingLogs.size() >= MAX_QUEUE_SIZE) {
                pendingLogs.poll();
                totalLogsDropped.incrementAndGet();
            }

            pendingLogs.offer(toResponse(entry));
            added++;
        }

        totalLogsQueued.addAndGet(added);

        // Log warning if we're dropping logs (indicates frontend can't keep up)
        long dropped = totalLogsDropped.get();
        if (dropped > 0 && dropped % 1000 == 0) {
            log.warn("WebSocket backpressure: {} logs dropped (queue full)", dropped);
        }
    }

    /**
     * Original method - kept for backward compatibility.
     * For high throughput, use broadcastBatch() instead.
     */
    public void broadcastLog(LogEntry logEntry) {
        if (!liveUpdatesEnabled.get()) {
            return;
        }

        broadcastBatch(List.of(logEntry));
    }

    /**
     * Smart broadcast: Uses batch for large lists, individual for small.
     */
    public void broadcastSmart(List<LogEntry> logEntries) {
        broadcastBatch(logEntries);  // Always use batch mode now
    }

    /**
     * Enable or disable live updates.
     * Useful for allowing users to pause updates during high load.
     */
    public void setLiveUpdatesEnabled(boolean enabled) {
        boolean previous = liveUpdatesEnabled.getAndSet(enabled);
        if (previous != enabled) {
            log.info("Live WebSocket updates {}", enabled ? "ENABLED" : "DISABLED");

            if (!enabled) {
                // Clear queue when disabling
                pendingLogs.clear();
            }
        }
    }

    public boolean isLiveUpdatesEnabled() {
        return liveUpdatesEnabled.get();
    }

    /**
     * Get statistics about WebSocket throughput.
     */
    public WebSocketStats getStats() {
        return new WebSocketStats(
                totalLogsQueued.get(),
                totalLogsBroadcast.get(),
                totalLogsDropped.get(),
                pendingLogs.size(),
                liveUpdatesEnabled.get()
        );
    }

    // ==================== INTERNAL ====================

    /**
     * Periodic task that flushes queued logs to WebSocket subscribers.
     * Runs every BROADCAST_INTERVAL_MS milliseconds.
     */
    private void flushPendingLogs() {
        if (pendingLogs.isEmpty()) {
            return;
        }

        List<LogEntryResponse> batch = new ArrayList<>();
        LogEntryResponse log;

        while ((log = pendingLogs.poll()) != null && batch.size() < MAX_LOGS_PER_BROADCAST) {
            batch.add(log);
        }

        if (!batch.isEmpty()) {
            try {
                messagingTemplate.convertAndSend("/topic/logs-batch", batch);
                totalLogsBroadcast.addAndGet(batch.size());

                int remaining = pendingLogs.size();
                if (remaining > 100) {
                    WebsocketService.log.debug("Broadcasted {} logs, {} still queued",
                            batch.size(), remaining);
                }
            } catch (Exception e) {
                WebsocketService.log.error("WebSocket broadcast failed: {}", e.getMessage());
                // Re-queue failed logs (at front, but respect max size)
                // Actually, don't re-queue - could cause infinite loop
            }
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

    // ==================== STATS RECORD ====================

    public record WebSocketStats(
            long totalQueued,
            long totalBroadcast,
            long totalDropped,
            int currentQueueSize,
            boolean liveEnabled
    ) {}
}