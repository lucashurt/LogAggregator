import React from 'react';

function MetricsDashboard({ searchResponse }) {
    // Use metrics from the search response (aggregated across all results)
    // Not just the current page
    const { levelCounts, serviceCounts, searchTimeMs, totalElements } = searchResponse;

    return (
        <div className="metrics-dashboard">
            <div className="metric-card">
                <h4>Page Logs</h4>
                <span className="metric-value">{totalElements.toLocaleString()}</span>
            </div>

            <div className="metric-card">
                <h4>Search Time</h4>
                <span className="metric-value">{searchTimeMs}ms</span>
            </div>

            <div className="metric-card">
                <h4>Errors</h4>
                <span className="metric-value error">
                    {levelCounts.ERROR || 0}
                </span>
            </div>

            <div className="metric-card">
                <h4>Warnings</h4>
                <span className="metric-value warning">
                    {levelCounts.WARNING || 0}
                </span>
            </div>

            <div className="metric-card">
                <h4>Info Logs</h4>
                <span className="metric-value">
                    {levelCounts.INFO || 0}
                </span>
            </div>

            <div className="metric-card">
                <h4>Debug Logs</h4>
                <span className="metric-value">
                    {levelCounts.DEBUG || 0}
                </span>
            </div>

            <div className="metric-card">
                <h4>Services</h4>
                <span className="metric-value">
                    {Object.keys(serviceCounts).length}
                </span>
            </div>
        </div>
    );
}

export default MetricsDashboard;