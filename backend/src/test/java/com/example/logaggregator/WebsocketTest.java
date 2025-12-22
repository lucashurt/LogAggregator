// backend/src/test/java/com/example/logaggregator/websocket/WebSocketIntegrationTest.java
package com.example.logaggregator;

import com.example.logaggregator.logs.services.WebsocketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketIntegrationTest extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WebsocketService WebSocketService;
    @Autowired
    private WebsocketService websocketService;

    @Test
    void shouldConnectToWebSocketAndReceiveMessages() throws Exception {
        BlockingQueue<Map> receivedMessages = new LinkedBlockingDeque<>();

        // Create WebSocket client
        List<Transport> transports = new ArrayList<>();
        transports.add(new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);

        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        String url = "ws://localhost:" + port + "/ws";

        System.out.println("Connecting to WebSocket at: " + url);

        StompSession session = stompClient.connectAsync(url, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                System.out.println("✅ WebSocket connected successfully!");
            }
        }).get(10, TimeUnit.SECONDS);

        // Subscribe to /topic/logs
        session.subscribe("/topic/logs", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                System.out.println("📨 Received message: " + payload);
                receivedMessages.add((Map) payload);
            }
        });

        // Wait for subscription to be active
        System.out.println("🎧 Subscribed to /topic/logs, waiting for subscription to be active...");
        Thread.sleep(1000);

        // MANUALLY send a test message instead of waiting for scheduled task
        System.out.println("📤 Manually triggering test message via LogWebSocketService...");
        com.example.logaggregator.logs.models.LogEntry testLog = new com.example.logaggregator.logs.models.LogEntry();
        testLog.setId(999L);
        testLog.setTimestamp(java.time.Instant.now());
        testLog.setServiceId("test-service");
        testLog.setLevel(com.example.logaggregator.logs.models.LogStatus.INFO);
        testLog.setMessage("WebSocket integration test message");
        testLog.setTraceId("test-trace-123");
        testLog.setCreatedAt(java.time.Instant.now());
        testLog.setMetadata(java.util.Map.of("test", true));

        websocketService.broadcastLog(testLog);

        // Wait for message with timeout
        System.out.println("⏳ Waiting for message (10 seconds timeout)...");
        Map message = receivedMessages.poll(10, TimeUnit.SECONDS);

        // Assert
        assertThat(message)
                .as("Should receive WebSocket message")
                .isNotNull();

        assertThat(message.get("id")).isEqualTo(999);
        assertThat(message.get("serviceId")).isEqualTo("test-service");
        assertThat(message.get("level")).isEqualTo("INFO");
        assertThat(message.get("message")).isEqualTo("WebSocket integration test message");

        System.out.println("✅ Test passed! WebSocket working correctly.");

        session.disconnect();
        stompClient.stop();
    }
}
