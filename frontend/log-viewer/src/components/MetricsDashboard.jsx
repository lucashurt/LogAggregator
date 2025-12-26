import React from 'react';

function MetricsDashboard({ levelCounts, serviceCounts, totalElements, searchTimeMs }) {

    return (
        <div className="metrics-dashboard">
            <div className="metric-card">
                <h4>Total Logs</h4>
                <span className="metric-value">{totalElements.toLocaleString()}</span>
                <span className="metric-subtitle">Across all pages</span>
            </div>

            <div className="metric-card">
                <h4>Search Time</h4>
                <span className="metric-value">{searchTimeMs}ms</span>
                <span className="metric-subtitle">Initial query</span>
            </div>

            <div className="metric-card">
                <h4>Errors</h4>
                <span className="metric-value error">
                    {levelCounts.ERROR || 0}
                </span>
                <span className="metric-subtitle">Total count</span>
            </div>

            <div className="metric-card">
                <h4>Warnings</h4>
                <span className="metric-value warning">
                    {levelCounts.WARNING || 0}
                </span>
                <span className="metric-subtitle">Total count</span>
            </div>

            <div className="metric-card">
                <h4>Info Logs</h4>
                <span className="metric-value">
                    {levelCounts.INFO || 0}
                </span>
                <span className="metric-subtitle">Total count</span>
            </div>

            <div className="metric-card">
                <h4>Debug Logs</h4>
                <span className="metric-value">
                    {levelCounts.DEBUG || 0}
                </span>
                <span className="metric-subtitle">Total count</span>
            </div>

            <div className="metric-card">
                <h4>Services</h4>
                <span className="metric-value">
                    {Object.keys(serviceCounts).length}
                </span>
                <span className="metric-subtitle">Unique services</span>
            </div>

        </div>
    );
}

export default MetricsDashboard;