import {format} from "date-fns";
import {useState} from "react";

function LogEntry({log}) {
    const [expanded, setExpanded] = useState(false);

    const getLevelClass = (level) => {
        return `log-level log-level-${level.toLowerCase()}`;
    }
    return (
        <div className={`log-entry ${expanded ? 'expanded' : ''}`}>
            <div className="log-header" onClick={() => setExpanded(!expanded)}>
                <span className={getLevelClass(log.level)}>{log.level}</span>
                <span className="log-time">
          {format(new Date(log.timestamp), 'HH:mm:ss.SSS')}
        </span>
                <span className="log-service">{log.serviceId}</span>
                <span className="log-message">{log.message}</span>
            </div>

            {expanded && (
                <div className="log-details">
                    <div className="detail-row">
                        <span className="detail-label">Trace ID:</span>
                        <span className="detail-value">{log.traceId || 'N/A'}</span>
                    </div>
                    <div className="detail-row">
                        <span className="detail-label">Created:</span>
                        <span className="detail-value">
              {format(new Date(log.createdAt), 'yyyy-MM-dd HH:mm:ss')}
            </span>
                    </div>
                    {log.metadata && Object.keys(log.metadata).length > 0 && (
                        <div className="detail-row">
                            <span className="detail-label">Metadata:</span>
                            <pre className="detail-value">
                {JSON.stringify(log.metadata, null, 2)}
              </pre>
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}

export default LogEntry;
