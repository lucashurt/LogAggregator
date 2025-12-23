import {useMemo} from "react";

function LogStream({logs,filters}){

    const validTimestamp = (log) => {
        return (
            log.startTimestamp >= filters.startTimestamp &&
            log.startTimestamp <= filters.endTimestamp &&
            log.endTimestamp >= filters.startTimestamp &&
            log.endTimestamp <= filters.endTimestamp
        );
    }

    const filteredLogs = useMemo(() => {
        return logs.filter(log => {
            if(filters.serviceId && !log.serviceId.includes(filters.serviceId)){
                return false;
            }
            if(filters.traceId && !log.traceId.includes(filters.traceId)){
                return false;
            }
            if(filters.level && log.level !== filters.level){
                return false;
            }
            if(filters.startTimestamp && filters.endTimestamp && !validTimestamp(log))
            if(filters.query && !log.message.toLowerCase().includes(filters.query)){
                return false;
            }
            return true;
        });
    },[logs,filters]);

    return (
        <div className="log-stream">
            <div className="stream-header">
                <h2>Live Log Stream</h2>
                <span className="log-count">{filteredLogs.length} logs</span>
            </div>

            <div className="log-list">
                {filteredLogs.length === 0 ? (
                    <div className="empty-state">
                        <p>Waiting for logs...</p>
                        <span>Logs will appear here in real-time</span>
                    </div>
                ) : (
                    filteredLogs.map(log => (
                        <LogEntry key={log.id} log={log} />
                    ))
                )}
            </div>
        </div>
    );
}

export default LogStream;
