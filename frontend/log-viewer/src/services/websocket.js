import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';

class WebSocketService {
    constructor() {
        this.client = null;
        this.connected = false;
        this.callbacks = [];
    }

    /**
     * Connect to WebSocket and subscribe to log batches.
     *
     * @param {Function} onBatchCallback - Called with an ARRAY of logs when batch arrives
     */
    connect(onBatchCallback) {
        const socket = new SockJS('http://localhost:8080/ws');

        this.client = new Client({
            webSocketFactory: () => socket,
            debug: (str) => {
                if (str.includes('CONNECTED') || str.includes('ERROR')) {
                    console.log('[STOMP]', str);
                }
            },
            reconnectDelay: 5000,
            heartbeatIncoming: 4000,
            heartbeatOutgoing: 4000,
        });

        this.client.onConnect = () => {
            console.log('✅ WebSocket Connected');
            this.connected = true;

            // Subscribe to batch endpoint
            // CRITICAL FIX: Backend sends List<LogEntryResponse>, so message.body is an ARRAY
            this.client.subscribe('/topic/logs-batch', (message) => {
                try {
                    const logs = JSON.parse(message.body);

                    // Validate it's actually an array
                    if (Array.isArray(logs)) {
                        // Pass the entire batch to the callback
                        onBatchCallback(logs);
                    } else {
                        // Fallback: wrap single log in array (shouldn't happen with batch endpoint)
                        console.warn('Expected array from /topic/logs-batch, got single object');
                        onBatchCallback([logs]);
                    }
                } catch (error) {
                    console.error('Failed to parse WebSocket message:', error);
                }
            });
        };

        this.client.onStompError = (frame) => {
            console.error('❌ STOMP error:', frame);
        };

        this.client.onWebSocketClose = () => {
            console.log('🔌 WebSocket disconnected');
            this.connected = false;
        };

        this.client.activate();
    }

    disconnect() {
        if (this.client) {
            this.client.deactivate();
            this.connected = false;
            console.log('🔌 WebSocket manually disconnected');
        }
    }

    isConnected() {
        return this.connected;
    }
}

const websocketService = new WebSocketService();
export default websocketService;