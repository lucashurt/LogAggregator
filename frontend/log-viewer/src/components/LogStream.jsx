import {useMemo} from "react";
import LogEntry from "./LogEntry";

function LogStream({logs, filters, bufferSize}){

    const validTimestamp = (log) => {
        if (!filters.startTimestamp && !filters.endTimestamp) {
            return true;
        }

        const logTime = new Date(log.timestamp).getTime();

        if (filters.startTimestamp && !filters.endTimestamp) {
            const startTime = new Date(filters.startTimestamp).getTime();
            return logTime >= startTime;
        }

        if (!filters.startTimestamp && filters.endTimestamp) {
            const endTime = new Date(filters.endTimestamp).getTime();
            return logTime <= endTime;
        }

        const startTime = new Date(filters.startTimestamp).getTime();
        const endTime = new Date(filters.endTimestamp).getTime();
        return logTime >= startTime && logTime <= endTime;
    }

    const filteredLogs = useMemo(() => {
        return logs.filter(log => {
            if(filters.serviceId && !log.serviceId.includes(filters.serviceId)){
                return false;
            }
            if(filters.traceId && !log.traceId?.includes(filters.traceId)){
                return false;
            }
            if(filters.level && log.level !== filters.level){
                return false;
            }
            if(filters.query && !log.message.toLowerCase().includes(filters.query.toLowerCase())){
                return false;
            }
            if((filters.startTimestamp || filters.endTimestamp) && !validTimestamp(log)){
                return false;
            }
            return true;
        });
    },[logs,filters]);

    return (
        <div className="log-stream">
            <div className="stream-header">
                <h2>📡 Live Monitor</h2>
                <span className="log-count">{filteredLogs.length} / {bufferSize} recent logs</span>
            </div>

            <div className="stream-info-banner">
                <div className="info-content">
                    <strong>ℹ️ Real-Time Monitoring Only</strong>
                    <p>
                        This view shows the last {bufferSize} logs as they arrive in real-time.
                        Older logs are automatically removed from this buffer.
                    </p>
                    <p>
                        <strong>🔍 To investigate past issues:</strong> Use the <strong>Search</strong> tab to query your complete log history.
                    </p>
                </div>
            </div>

            <div className="log-list">
                {filteredLogs.length === 0 ? (
                    <div className="empty-state">
                        <p>Waiting for new logs...</p>
                        <span>Logs will appear here in real-time as they're generated</span>
                    </div>
                ) : (
                    filteredLogs.map(log => (
                        <LogEntry key={`${log.id}-${log.timestamp}`} log={log} />
                    ))
                )}
            </div>
        </div>
    );
}

export default LogStream;