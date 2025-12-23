import {searchLogs} from "../services/api";
import {useState} from "react";
import LogEntry from "./LogEntry";
import MetricsDashboard from "./MetricsDashboard";

function LogSearch({ filters, setFilters }) {
    const [results, setResults] = useState([]);
    const [loading, setLoading] = useState(false);
    const [page, setPage] = useState(0);
    const [pageSize, setPageSize] = useState(100);
    const [totalPages, setTotalPages] = useState(0);
    const [totalElements, setTotalElements] = useState(0);
    const [searchExecuted, setSearchExecuted] = useState(false);

    // Convert datetime-local format to ISO format for backend
    const convertToISO = (datetimeLocal) => {
        if (!datetimeLocal) return null;
        // datetime-local gives us "2025-12-23T10:30"
        // Backend needs "2025-12-23T10:30:00Z"
        return new Date(datetimeLocal).toISOString();
    };

    const handleSearch = async (newPage = 0) => {
        setLoading(true);
        setPage(newPage);

        try {
            // Convert timestamp filters to ISO format
            const searchParams = {
                serviceId: filters.serviceId || undefined,
                level: filters.level || undefined,
                traceId: filters.traceId || undefined,
                startTime: filters.startTimestamp ? convertToISO(filters.startTimestamp) : undefined,
                endTime: filters.endTimestamp ? convertToISO(filters.endTimestamp) : undefined,
                query: filters.query || undefined,
                page: newPage,
                size: pageSize
            };

            // Remove undefined values
            Object.keys(searchParams).forEach(key =>
                searchParams[key] === undefined && delete searchParams[key]
            );

            const data = await searchLogs(searchParams);

            setResults(data.logs);
            setTotalPages(data.totalPages);
            setTotalElements(data.totalElements);
            setSearchExecuted(true);

            console.log(`Found ${data.totalElements} total logs, showing page ${newPage + 1}/${data.totalPages}`);
        } catch (error) {
            console.error('Search failed:', error);
            alert('Search failed. Check console for details.');
        } finally {
            setLoading(false);
        }
    };

    const setTimeRange = (hours) => {
        const now = new Date();
        const start = new Date(now.getTime() - hours * 60 * 60 * 1000);

        // Format for datetime-local input: YYYY-MM-DDTHH:mm
        const formatDateTime = (date) => {
            const pad = (n) => n.toString().padStart(2, '0');
            return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
        };

        // Properly update parent state
        setFilters(prev => ({
            ...prev,
            startTimestamp: formatDateTime(start),
            endTimestamp: formatDateTime(now)
        }));
    };

    const handlePageSizeChange = (newSize) => {
        setPageSize(newSize);
        if (searchExecuted) {
            handleSearch(0);
        }
    };

    const goToPage = (pageNum) => {
        handleSearch(pageNum);
    };

    return (
        <div className="log-search">
            <div className="search-header">
                <div className="search-info">
                    <h2>Search Logs</h2>
                    <p className="search-description">
                        Query your entire log database with advanced filtering
                    </p>
                    {searchExecuted && (
                        <span className="search-stats">
                            {totalElements === 0
                                ? "No logs found"
                                : `Found ${totalElements.toLocaleString()} logs - Page ${page + 1} of ${totalPages}`
                            }
                        </span>
                    )}
                </div>
                <div className="search-controls">
                    <div className="quick-time-filters">
                        <button onClick={() => setTimeRange(1)} className="time-preset">1h</button>
                        <button onClick={() => setTimeRange(6)} className="time-preset">6h</button>
                        <button onClick={() => setTimeRange(24)} className="time-preset">24h</button>
                        <button onClick={() => setTimeRange(168)} className="time-preset">7d</button>
                    </div>
                    <select
                        value={pageSize}
                        onChange={(e) => handlePageSizeChange(Number(e.target.value))}
                        disabled={loading}
                    >
                        <option value={50}>50 / page</option>
                        <option value={100}>100 / page</option>
                        <option value={200}>200 / page</option>
                        <option value={500}>500 / page</option>
                    </select>
                    <button onClick={() => handleSearch(0)} disabled={loading} className="search-btn">
                        {loading ? 'Searching...' : 'Search'}
                    </button>
                </div>
            </div>

            {searchExecuted && results.length > 0 && (
                <>
                    <MetricsDashboard logs={results} />

                    <div className="log-list">
                        {results.map(log => (
                            <LogEntry key={log.id} log={log} />
                        ))}
                    </div>

                    <div className="pagination">
                        <button
                            onClick={() => goToPage(0)}
                            disabled={page === 0 || loading}
                        >
                            First
                        </button>
                        <button
                            onClick={() => goToPage(page - 1)}
                            disabled={page === 0 || loading}
                        >
                            Previous
                        </button>

                        <div className="page-numbers">
                            {page > 2 && <span>...</span>}
                            {Array.from({ length: Math.min(5, totalPages) }, (_, i) => {
                                let pageNum = page - 2 + i;
                                if (pageNum < 0) pageNum = i;
                                if (pageNum >= totalPages) return null;

                                return (
                                    <button
                                        key={pageNum}
                                        onClick={() => goToPage(pageNum)}
                                        disabled={loading}
                                        className={pageNum === page ? 'active' : ''}
                                    >
                                        {pageNum + 1}
                                    </button>
                                );
                            })}
                            {page < totalPages - 3 && <span>...</span>}
                        </div>

                        <button
                            onClick={() => goToPage(page + 1)}
                            disabled={page >= totalPages - 1 || loading}
                        >
                            Next
                        </button>
                        <button
                            onClick={() => goToPage(totalPages - 1)}
                            disabled={page >= totalPages - 1 || loading}
                        >
                            Last
                        </button>
                    </div>

                    <div className="pagination-summary">
                        Showing {page * pageSize + 1} - {Math.min((page + 1) * pageSize, totalElements)} of {totalElements.toLocaleString()}
                    </div>
                </>
            )}

            {!searchExecuted && (
                <div className="empty-state">
                    <div className="search-instructions">
                        <h3>Getting Started</h3>
                        <div className="instructions-grid">
                            <div className="instruction-card">
                                <div className="instruction-icon">⏱️</div>
                                <h4>Time Range</h4>
                                <p>Use quick filters (1h, 6h, 24h) or set custom date range</p>
                            </div>
                            <div className="instruction-card">
                                <div className="instruction-icon">🎯</div>
                                <h4>Service Filter</h4>
                                <p>Filter by specific service (auth-service, payment-service, etc.)</p>
                            </div>
                            <div className="instruction-card">
                                <div className="instruction-icon">🚨</div>
                                <h4>Log Level</h4>
                                <p>Show only ERROR, WARNING, INFO, or DEBUG logs</p>
                            </div>
                            <div className="instruction-card">
                                <div className="instruction-icon">🔍</div>
                                <h4>Full-Text Search</h4>
                                <p>Search message content: "timeout", "connection failed", etc.</p>
                            </div>
                            <div className="instruction-card">
                                <div className="instruction-icon">🔗</div>
                                <h4>Trace ID</h4>
                                <p>Follow a request across services with trace ID</p>
                            </div>
                            <div className="instruction-card">
                                <div className="instruction-icon">⚡</div>
                                <h4>Performance</h4>
                                <p>Searches powered by Elasticsearch for millisecond response times</p>
                            </div>
                        </div>
                        <button onClick={() => handleSearch(0)} className="search-btn-large">
                            Search All Logs
                        </button>
                    </div>
                </div>
            )}

            {searchExecuted && results.length === 0 && (
                <div className="empty-state">
                    <p>No logs found matching your criteria</p>
                    <span>Try adjusting your filters or expanding the time range</span>
                </div>
            )}
        </div>
    );
}

export default LogSearch;