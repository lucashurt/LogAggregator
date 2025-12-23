import logo from './logo.svg';
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
        query: ''
    });
    const [logs, setLogs] = useState([]);
    const [wsConnected, setWsConnected] = useState(false);

    useEffect(() => {
        websocketService.connect((newLog) => {
            setLogs(prevLogs => [newLog, ...prevLogs].slice(0, 500)); // Keep last 500
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

                    {view === 'stream' ? (
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
