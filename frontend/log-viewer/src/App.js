import './App.css';
import {useEffect, useState, useRef} from "react";
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

    // Pause/Resume functionality
    const [isPaused, setIsPaused] = useState(false);
    const [bufferedLogs, setBufferedLogs] = useState([]);
    const pausedLogsRef = useRef([]);

    const REALTIME_BUFFER_SIZE = 2500;

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

            // Reset pause state when switching to stream
            if (newView === 'stream') {
                setIsPaused(false);
                setBufferedLogs([]);
                pausedLogsRef.current = [];
            }
        }
    };

    const togglePause = () => {
        setIsPaused(prev => !prev);

        if (isPaused) {
            console.log(`▶️ Resuming stream. ${pausedLogsRef.current.length} logs buffered while paused.`);
        } else {
            console.log('⏸️ Stream paused. Logs will buffer in background.');
            pausedLogsRef.current = [];
            setBufferedLogs([]);
        }
    };

    const resumeWithBuffered = () => {
        console.log(`▶️ Resuming and adding ${pausedLogsRef.current.length} buffered logs`);
        setRealtimeLogs(prev => {
            const combined = [...pausedLogsRef.current, ...prev];
            return combined.slice(0, REALTIME_BUFFER_SIZE);
        });
        setIsPaused(false);
        setBufferedLogs([]);
        pausedLogsRef.current = [];
    };

    const resumeDiscardBuffered = () => {
        console.log(`▶️ Resuming and discarding ${pausedLogsRef.current.length} buffered logs`);
        setIsPaused(false);
        setBufferedLogs([]);
        pausedLogsRef.current = [];
    };

    const clearAndResume = () => {
        console.log('🗑️ Clearing current logs and resuming fresh');
        setRealtimeLogs([]);
        setIsPaused(false);
        setBufferedLogs([]);
        pausedLogsRef.current = [];
    };

    useEffect(() => {
        /**
         * CRITICAL FIX: The WebSocket now receives BATCHES of logs (arrays)
         * instead of individual logs. We need to handle this correctly.
         */
        websocketService.connect((newLogs) => {
            // newLogs is now an ARRAY of logs from the batch endpoint
            if (!Array.isArray(newLogs) || newLogs.length === 0) {
                return;
            }

            if (isPaused) {
                // Add batch to paused buffer (newest first)
                pausedLogsRef.current = [...newLogs, ...pausedLogsRef.current].slice(0, 500);
                setBufferedLogs(pausedLogsRef.current);
            } else {
                // Add batch to realtime logs (newest first)
                setRealtimeLogs(prevLogs => {
                    return [...newLogs, ...prevLogs].slice(0, REALTIME_BUFFER_SIZE);
                });
            }
        });

        setWsConnected(true);

        return () => {
            websocketService.disconnect();
        }
    }, [isPaused]);

    return (
        <div className="app">
            <header className="app-header">
                <h1>Log Aggregator</h1>
                <div className="header-controls">
                    <span className={`connection-status ${wsConnected ? 'connected' : 'disconnected'}`}>
                        {wsConnected ? 'Live' : 'Disconnected'}
                    </span>

                    {view === 'stream' && (
                        <>
                            <span className="log-count-badge">
                                {realtimeLogs.length} / {REALTIME_BUFFER_SIZE}
                            </span>

                            <button
                                className={`pause-btn ${isPaused ? 'paused' : 'live'}`}
                                onClick={togglePause}
                                title={isPaused ? "Resume stream" : "Pause stream"}
                            >
                                {isPaused ? 'Resume' : 'Pause'}
                            </button>

                            {isPaused && bufferedLogs.length > 0 && (
                                <span className="buffer-indicator">
                                    {bufferedLogs.length} buffered
                                </span>
                            )}
                        </>
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
                            isPaused={isPaused}
                            bufferedCount={bufferedLogs.length}
                            onResumeWithBuffered={resumeWithBuffered}
                            onResumeDiscardBuffered={resumeDiscardBuffered}
                            onClearAndResume={clearAndResume}
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