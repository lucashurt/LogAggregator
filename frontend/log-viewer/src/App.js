import './App.css';
import {useEffect, useState} from "react";
import websocketService from "./services/websocket";
import LogStream from "./components/LogStream";
import FilterPanel from "./components/FilterPanel";
import LogSearch from "./components/LogSearch";

function App() {
    const [view, setView] = useState('stream');
    const [filters, setFilters] = useState({
        serviceId: '',
        level: '',
        traceId: '',
        startTimestamp: '',
        endTimestamp: '',
        query: ''
    });

    // Live Stream: Small buffer for real-time monitoring
    const [realtimeLogs, setRealtimeLogs] = useState([]);
    const [wsConnected, setWsConnected] = useState(false);

    const REALTIME_BUFFER_SIZE = 1000;

    // FIX: Clear filters when switching views to prevent bleed
    const handleViewChange = (newView) => {
        if (newView !== view) {
            console.log(`🔄 Switching from ${view} to ${newView} - clearing filters`);
            setFilters({
                serviceId: '',
                level: '',
                traceId: '',
                startTimestamp: '',
                endTimestamp: '',
                query: ''
            });
            setView(newView);
        }
    };

    useEffect(() => {
        websocketService.connect((newLog) => {
            setRealtimeLogs(prevLogs => {
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
                        {wsConnected ? 'Live' : 'Disconnected'}
                    </span>

                    {view === 'stream' && (
                        <span className="log-count-badge">
                            {realtimeLogs.length} / {REALTIME_BUFFER_SIZE}
                        </span>
                    )}

                    <button
                        className={view === 'search' ? 'active' : ''}
                        onClick={() => handleViewChange('search')}
                    >
                        Search
                    </button>
                    <button
                        className={view === 'stream' ? 'active' : ''}
                        onClick={() => handleViewChange('stream')}
                    >
                        Live Stream
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
                        <LogSearch
                            filters={filters}
                            setFilters={setFilters}
                        />
                    )}
                </main>
            </div>
        </div>
    );
}

export default App;