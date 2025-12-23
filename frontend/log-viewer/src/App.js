import './App.css';
import {useEffect, useState} from "react";
import websocketService from "./services/websocket";
import LogStream from "./components/LogStream";
import FilterPanel from "./components/FilterPanel";
import LogSearch from "./components/LogSearch";

function App() {
    const [view, setView] = useState('search'); // ← DEFAULT to Search (not stream)
    const [filters, setFilters] = useState({
        serviceId: '',
        level: '',
        traceId: '',
        startTimestamp: '',
        endTimestamp: '',
        query: ''
    });

    // Live Stream: Small buffer for real-time monitoring ONLY
    const [realtimeLogs, setRealtimeLogs] = useState([]);
    const [wsConnected, setWsConnected] = useState(false);

    // Configuration for Live Stream
    const REALTIME_BUFFER_SIZE = 1000; // Small buffer - only for "what's happening NOW"

    useEffect(() => {
        // Connect WebSocket for real-time monitoring
        websocketService.connect((newLog) => {
            setRealtimeLogs(prevLogs => {
                // Keep only recent logs (last 500)
                return [newLog, ...prevLogs].slice(0, REALTIME_BUFFER_SIZE);
            });
        });

        setWsConnected(true);

        return () => {
            websocketService.disconnect();
        }
    }, []);

    return (
        <div className="app">
            <header className="app-header">
                <h1>Log Aggregator</h1>
                <div className="header-controls">
                    <span className={`connection-status ${wsConnected ? 'connected' : 'disconnected'}`}>
                        {wsConnected ? '● Live' : '○ Disconnected'}
                    </span>

                    {view === 'stream' && (
                        <span className="log-count-badge">
                            {realtimeLogs.length} / {REALTIME_BUFFER_SIZE} recent logs
                        </span>
                    )}

                    <button
                        className={view === 'search' ? 'active' : ''}
                        onClick={() => setView('search')}
                    >
                        🔍 Search & Investigate
                    </button>
                    <button
                        className={view === 'stream' ? 'active' : ''}
                        onClick={() => setView('stream')}
                    >
                        📡 Live Monitor
                    </button>
                </div>
            </header>

            <div className="app-body">
                <FilterPanel
                    filters={filters}
                    setFilters={setFilters}
                    view={view}
                />

                <main className="main-content">
                    {view === 'stream' ? (
                        <LogStream
                            logs={realtimeLogs}
                            filters={filters}
                            bufferSize={REALTIME_BUFFER_SIZE}
                        />
                    ) : (
                        <LogSearch filters={filters} />
                    )}
                </main>
            </div>
        </div>
    );
}

export default App;