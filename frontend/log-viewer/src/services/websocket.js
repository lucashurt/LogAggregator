import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';

class WebsocketService {
    constructor() {
        this.client = null;
        this.connected = false;
        this.callbacks = [];
    }

    connect(onMessageCallback){
        const socket = new SockJS('http://localhost:8080/ws');

        this.client = new Client({
            webSocketFactory: () => socket,
            debug: (str) => {
                console.log('[STOMP]', str);
            },
            reconnectDelay: 5000,
            heartbeatIncoming: 4000,
            heartbeatOutgoing: 4000,
        });

        this.client.onConnect = () => {
            console.log('WebSocket Connected');
            this.connected = true;

            // Subscribe to /topic/logs
            this.client.subscribe('/topic/logs', (message) => {
                const log = JSON.parse(message.body);
                onMessageCallback(log);
            });
        };
        this.client.onStompError = (frame) => {
            console.error('STOMP error:', frame);
        };

        this.client.activate();
    }
    disconnect() {
        if (this.client) {
            this.client.deactivate();
            this.connected = false;
        }
    }

    isConnected() {
        return this.connected;
    }
}

export default new WebSocketService();
