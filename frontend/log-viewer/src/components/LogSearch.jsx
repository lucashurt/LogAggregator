import {searchLogs} from "../services/api";
import {useState} from "react";
import LogEntry from "./LogEntry";

function LogSearch({ filters }) {
    const [results, setResults] = useState([]);
    const [loading, setLoading] = useState(false);
    const [page, setPage] = useState(0);
    const [pageSize, setPageSize] = useState(100);
    const [totalPages, setTotalPages] = useState(0);
    const [totalElements, setTotalElements] = useState(0);
    const [searchExecuted, setSearchExecuted] = useState(false);

    const handleSearch = async (newPage = 0) => {
        setLoading(true);
        setPage(newPage);

        try {
            const data = await searchLogs({
                ...filters,
                page: newPage,
                size: pageSize
            });

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

        // Trigger parent's filter update
        const event = new CustomEvent('timeRangeSelected', {
            detail: {
                startTimestamp: start.toISOString(),
                endTimestamp: now.toISOString()
            }
        });
        window.dispatchEvent(event);

        // Update local display (this is a workaround - in real app, use proper state management)
        document.querySelector('input[type="datetime-local"]:nth-of-type(1)').value = formatDateTime(start);
        document.querySelector('input[type="datetime-local"]:nth-of-type(2)').value = formatDateTime(now);
    };

    const handlePageSizeChange = (newSize) => {
        setPageSize(newSize);
        if (searchExecuted) {
            handleSearch(0); // Re-run search with new page size
        }
    };

    const goToPage = (pageNum) => {
        handleSearch(pageNum);
    };

    return (
        <div className="log-search">
            <div className="search-header">
                <div className="search-info">
                    <h2>🔍 Search Historical Logs</h2>
                    <p className="search-description">
                        Query your entire log database. Use filters to narrow down issues.
                    </p>
                    {searchExecuted && (
                        <span className="search-stats">
                            {totalElements === 0
                                ? "No logs found"
                                : `Found ${totalElements.toLocaleString()} logs - Showing page ${page + 1} of ${totalPages}`
                            }
                        </span>
                    )}
                </div>
                <div className="search-controls">
                    <div className="quick-time-filters">
                        <button onClick={() => setTimeRange(1)} className="time-preset">Last Hour</button>
                        <button onClick={() => setTimeRange(6)} className="time-preset">Last 6 Hours</button>
                        <button onClick={() => setTimeRange(24)} className="time-preset">Last 24 Hours</button>
                    </div>
                    <select
                        value={pageSize}
                        onChange={(e) => handlePageSizeChange(Number(e.target.value))}
                        disabled={loading}
                    >
                        <option value={50}>50 per page</option>
                        <option value={100}>100 per page</option>
                        <option value={200}>200 per page</option>
                        <option value={500}>500 per page</option>
                    </select>
                    <button onClick={() => handleSearch(0)} disabled={loading} className="search-btn">
                        {loading ? '⏳ Searching...' : '🔍 Search'}
                    </button>
                </div>
            </div>

            {searchExecuted && results.length > 0 && (
                <>
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
                            ⏮ First
                        </button>
                        <button
                            onClick={() => goToPage(page - 1)}
                            disabled={page === 0 || loading}
                        >
                            ← Previous
                        </button>

                        <div className="page-numbers">
                            {/* Show page numbers */}
                            {page > 2 && <span>...</span>}
                            {Array.from({ length: Math.min(5, totalPages) }, (_, i) => {
                                // Show pages around current page
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
                            Next →
                        </button>
                        <button
                            onClick={() => goToPage(totalPages - 1)}
                            disabled={page >= totalPages - 1 || loading}
                        >
                            Last ⏭
                        </button>
                    </div>

                    <div className="pagination-summary">
                        Showing logs {page * pageSize + 1} - {Math.min((page + 1) * pageSize, totalElements)} of {totalElements.toLocaleString()}
                    </div>
                </>
            )}

            {!searchExecuted && (
                <div className="empty-state">
                    <div className="search-instructions">
                        <h3>💡 How to Use Search</h3>
                        <ul>
                            <li><strong>Time Range:</strong> Use "Last Hour" / "Last 24 Hours" or set custom dates</li>
                            <li><strong>Service Filter:</strong> Filter by specific service (e.g., "payment-service")</li>
                            <li><strong>Log Level:</strong> Show only ERROR, WARNING, etc.</li>
                            <li><strong>Text Search:</strong> Search message content (e.g., "timeout", "failed")</li>
                            <li><strong>Trace ID:</strong> Follow a specific request across services</li>
                        </ul>
                        <p className="search-tip">
                            💡 <strong>Tip:</strong> Start with a time range, then add more filters to narrow down.
                        </p>
                        <button onClick={() => handleSearch(0)} className="search-btn-large">
                            🔍 Search All Logs
                        </button>
                    </div>
                </div>
            )}

            {searchExecuted && results.length === 0 && (
                <div className="empty-state">
                    <p>No logs found matching your criteria</p>
                    <span>Try adjusting your filters</span>
                </div>
            )}
        </div>
    );
}

export default LogSearch;