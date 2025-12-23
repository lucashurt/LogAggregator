import {useMemo} from "react";
import LogEntry from "./LogEntry";

function LogStream({logs,filters}){

    const validTimestamp = (log) => {
        // If no timestamp filters are set, always pass
        if (!filters.startTimestamp && !filters.endTimestamp) {
            return true;
        }

        const logTime = new Date(log.timestamp).getTime();

        // If only start time is set
        if (filters.startTimestamp && !filters.endTimestamp) {
            const startTime = new Date(filters.startTimestamp).getTime();
            return logTime >= startTime;
        }

        // If only end time is set
        if (!filters.startTimestamp && filters.endTimestamp) {
            const endTime = new Date(filters.endTimestamp).getTime();
            return logTime <= endTime;
        }

        // If both are set
        const startTime = new Date(filters.startTimestamp).getTime();
        const endTime = new Date(filters.endTimestamp).getTime();
        return logTime >= startTime && logTime <= endTime;
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
