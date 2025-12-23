import React, { useMemo } from 'react';

function MetricsDashboard({ logs }) {
    const metrics = useMemo(() => {
        const levelCounts = logs.reduce((acc, log) => {
            acc[log.level] = (acc[log.level] || 0) + 1;
            return acc;
        }, {});

        const serviceCounts = logs.reduce((acc, log) => {
            acc[log.serviceId] = (acc[log.serviceId] || 0) + 1;
            return acc;
        }, {});

        return { levelCounts, serviceCounts };
    }, [logs]);

    return (
        <div className="metrics-dashboard">
            <div className="metric-card">
                <h4>Total Logs</h4>
                <span className="metric-value">{logs.length}</span>
            </div>

            <div className="metric-card">
                <h4>Errors</h4>
                <span className="metric-value error">
                    {metrics.levelCounts.ERROR || 0}
                </span>
            </div>

            <div className="metric-card">
                <h4>Warnings</h4>
                <span className="metric-value warning">
                    {metrics.levelCounts.WARNING || 0}
                </span>
            </div>

            <div className="metric-card">
                <h4>Services</h4>
                <span className="metric-value">
                    {Object.keys(metrics.serviceCounts).length}
                </span>
            </div>
        </div>
    );
}

export default MetricsDashboard;