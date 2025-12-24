import {useMemo, useRef, useEffect} from "react";
import LogEntry from "./LogEntry";

function LogStream({
                       logs,
                       filters,
                       bufferSize,
                       isPaused,
                       bufferedCount,
                       onResumeWithBuffered,
                       onResumeDiscardBuffered,
                       onClearAndResume
                   }){
    const logListRef = useRef(null);

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
    },[logs, filters]);

    // Auto-scroll to top when new logs arrive (only when not paused)
    useEffect(() => {
        if (!isPaused && logListRef.current) {
            logListRef.current.scrollTop = 0;
        }
    }, [logs, isPaused]);

    return (
        <div className="log-stream">
            <div className="stream-header">
                <h2>
                    {isPaused ? 'Paused Monitor' : 'Live Monitor'}
                </h2>
                <span className="log-count">
                    {filteredLogs.length} / {bufferSize} recent logs
                </span>
            </div>

            <div className="log-list" ref={logListRef}>
                {/* Resume Options Banner (shows when paused with buffered logs) */}
                {isPaused && bufferedCount > 0 && (
                    <div className="pause-banner">
                        <div className="pause-info">
                            <strong>Stream Paused</strong>
                            <p>{bufferedCount} new logs received while paused</p>
                        </div>
                        <div className="pause-actions">
                            <button
                                className="resume-option primary"
                                onClick={onResumeWithBuffered}
                                title="Add buffered logs to current view and resume"
                            >
                                Resume + Add Buffered ({bufferedCount})
                            </button>
                            <button
                                className="resume-option secondary"
                                onClick={onResumeDiscardBuffered}
                                title="Keep current view and resume (discard buffered logs)"
                            >
                                Resume (Keep Current View)
                            </button>
                            <button
                                className="resume-option danger"
                                onClick={onClearAndResume}
                                title="Clear everything and start fresh"
                            >
                                Clear & Resume Fresh
                            </button>
                        </div>
                    </div>
                )}

                {/* Paused indicator (shows when paused with no buffered logs yet) */}
                {isPaused && bufferedCount === 0 && (
                    <div className="pause-banner info">
                        <div className="pause-info">
                            <strong>Stream Paused</strong>
                            <p>New logs will buffer in the background. Click Resume to continue.</p>
                        </div>
                    </div>
                )}

                <div className="stream-info-banner">
                    <div className="info-content">
                        <div className="info-item">
                            <span className="info-label">Real-Time Buffer</span>
                            <span className="info-text">Shows last {bufferSize} logs as they arrive. Older logs are automatically removed.</span>
                        </div>
                        <div className="info-item">
                            <span className="info-label">Historical Search</span>
                            <span className="info-text">Use the <span className="highlight">Search</span> tab to query your complete log history.</span>
                        </div>
                        <div className="info-item">
                            <span className="info-label">Pause Control</span>
                            <span className="info-text">Click <span className="highlight">Pause</span> to freeze the stream and inspect errors — logs buffer in background.</span>
                        </div>
                    </div>
                </div>


                {filteredLogs.length === 0 ? (
                    <div className="empty-state">
                        {isPaused ? (
                            <>
                                <p>Stream Paused</p>
                                <span>Click Resume to see new logs</span>
                            </>
                        ) : (
                            <>
                                <p>Waiting for new logs...</p>
                                <span>Logs will appear here in real-time as they're generated</span>
                            </>
                        )}
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