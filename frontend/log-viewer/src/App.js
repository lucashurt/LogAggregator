import logo from './logo.svg';
import './App.css';
import {useEffect, useState} from "react";
import websocketService from "./services/websocket";
import LogStream from "./components/LogStream";
import FilterPanel from "./components/FilterPanel";
import LogSearch from "./components/LogSearch";
import {searchLogs} from "./services/api";
import MetricsDashboard from "./components/MetricsDashboard";

function App() {
    const [view, setView] = useState('stream');
    const [filters, setFilters] = useState({
        serviceId: '',
        level: '',
        query: ''
    });
    const [logs, setLogs] = useState([]);
    const [wsConnected, setWsConnected] = useState(false);
    const [loading, setLoading] = useState(true);

    // Configuration
    const MAX_LOGS_IN_MEMORY = 5000; // Increased from 500
    const INITIAL_LOAD_SIZE = 1000;   // Load last 1000 logs on startup

    useEffect(() => {
        // STEP 1: Load historical logs first
        const loadHistoricalLogs = async () => {
            try {
                setLoading(true);
                console.log('Loading historical logs...');

                // Fetch most recent logs from backend
                const response = await searchLogs({
                    page: 0,
                    size: INITIAL_LOAD_SIZE
                });

                setLogs(response.logs);
                console.log(`✅ Loaded ${response.logs.length} historical logs`);
            } catch (error) {
                console.error('❌ Failed to load historical logs:', error);
            } finally {
                setLoading(false);
            }
        };

        loadHistoricalLogs();

        // STEP 2: Start WebSocket for real-time updates
        websocketService.connect((newLog) => {
            setLogs(prevLogs => {
                // Prevent duplicates (check by ID if available)
                const isDuplicate = prevLogs.some(log => log.id === newLog.id);
                if (isDuplicate) return prevLogs;

                // Add new log and maintain buffer size
                return [newLog, ...prevLogs].slice(0, MAX_LOGS_IN_MEMORY);
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
                    <span className="log-count-badge">
                        {logs.length} / {MAX_LOGS_IN_MEMORY} logs in memory
                    </span>
                    <button
                        className={view === 'stream' ? 'active' : ''}
                        onClick={() => setView('stream')}
                    >
                        Live Stream
                    </button>
                    <button
                        className={view === 'search' ? 'active' : ''}
                        onClick={() => setView('search')}
                    >
                        Search
                    </button>
                </div>
            </header>

            <div className="app-body">
                <FilterPanel filters={filters} setFilters={setFilters} />

                <main className="main-content">
                    <MetricsDashboard logs={logs} />

                    {loading && view === 'stream' ? (
                        <div className="loading-state">
                            <p>Loading historical logs...</p>
                        </div>
                    ) : view === 'stream' ? (
                        <LogStream logs={logs} filters={filters} />
                    ) : (
                        <LogSearch filters={filters} />
                    )}
                </main>
            </div>
        </div>
    );
}

export default App;