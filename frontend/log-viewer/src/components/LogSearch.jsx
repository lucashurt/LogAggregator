import {searchLogs} from "../services/api";
import {useState} from "react";
import LogEntry from "./LogEntry";
import MetricsDashboard from "./MetricsDashboard";

function LogSearch({ filters, setFilters }) {
    const [searchResponse, setSearchResponse] = useState(null);
    const [loading, setLoading] = useState(false);
    const [page, setPage] = useState(0);
    const [pageSize, setPageSize] = useState(100);
    const [searchExecuted, setSearchExecuted] = useState(false);

    // NEW: Store initial search metadata separately (doesn't update on pagination)
    const [initialSearchMetadata, setInitialSearchMetadata] = useState(null);

    // Convert datetime-local format to ISO format for backend
    const convertToISO = (datetimeLocal) => {
        if (!datetimeLocal) return null;
        return new Date(datetimeLocal).toISOString();
    };

    const handleSearch = async (newPage = 0, isNewSearch = true) => {
        setLoading(true);
        setPage(newPage);

        try {
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

            setSearchResponse(data);
            setSearchExecuted(true);

            // CRITICAL FIX: Only update initial metadata on NEW searches, not pagination
            if (isNewSearch) {
                setInitialSearchMetadata({
                    totalElements: data.totalElements,
                    totalPages: data.totalPages,
                    searchTimeMs: data.searchTimeMs,
                    levelCounts: data.levelCounts,
                    serviceCounts: data.serviceCounts
                });
                console.log(`NEW SEARCH: Found ${data.totalElements} total logs in ${data.searchTimeMs}ms`);
            } else {
                console.log(`Page ${newPage + 1} loaded (using cached metrics)`);
            }
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

        const formatDateTime = (date) => {
            const pad = (n) => n.toString().padStart(2, '0');
            return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
        };

        setFilters(prev => ({
            ...prev,
            startTimestamp: formatDateTime(start),
            endTimestamp: formatDateTime(now)
        }));
    };

    const handlePageSizeChange = (newSize) => {
        setPageSize(newSize);
        if (searchExecuted) {
            // This is a new search with different page size
            handleSearch(0, true);
        }
    };

    const goToPage = (pageNum) => {
        // This is just pagination, not a new search
        handleSearch(pageNum, false);
    };

    // Use initial metadata for display (doesn't change on pagination)
    const displayMetadata = initialSearchMetadata || {
        totalElements: 0,
        totalPages: 0,
        searchTimeMs: 0,
        levelCounts: {},
        serviceCounts: {}
    };

    return (
        <div className="log-search">
            <div className="search-header">
                <div className="search-info">
                    <h2>Search Logs</h2>
                    <p className="search-description">
                        Query your entire log database with advanced filtering
                    </p>
                    {searchExecuted && searchResponse && (
                        <span className="search-stats">
                            {displayMetadata.totalElements === 0
                                ? "No logs found"
                                : `Found ${displayMetadata.totalElements.toLocaleString()} logs in ${displayMetadata.searchTimeMs}ms - Page ${page + 1} of ${displayMetadata.totalPages}`
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
                    <button onClick={() => handleSearch(0, true)} disabled={loading} className="search-btn">
                        {loading ? 'Searching...' : 'Search'}
                    </button>
                </div>
            </div>

            {searchExecuted && searchResponse && searchResponse.logs.length > 0 && (
                <>
                    {/* Pass initial metadata that doesn't change on pagination */}
                    <MetricsDashboard
                        levelCounts={displayMetadata.levelCounts}
                        serviceCounts={displayMetadata.serviceCounts}
                        totalElements={displayMetadata.totalElements}
                        searchTimeMs={displayMetadata.searchTimeMs}
                    />

                    <div className="log-list">
                        {searchResponse.logs.map(log => (
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
                            {(() => {
                                const totalPages = displayMetadata.totalPages;
                                const currentPage = page;
                                const pageNumbers = [];

                                // Calculate range of pages to show (5 pages centered on current)
                                let startPage = Math.max(0, currentPage - 2);
                                let endPage = Math.min(totalPages - 1, startPage + 4);

                                // Adjust if we're near the end
                                if (endPage - startPage < 4) {
                                    startPage = Math.max(0, endPage - 4);
                                }

                                // Show ellipsis at start if needed
                                if (startPage > 0) {
                                    pageNumbers.push(
                                        <button key={0} onClick={() => goToPage(0)} disabled={loading}>
                                            1
                                        </button>
                                    );
                                    if (startPage > 1) {
                                        pageNumbers.push(<span key="ellipsis-start">...</span>);
                                    }
                                }

                                // Show page numbers
                                for (let i = startPage; i <= endPage; i++) {
                                    pageNumbers.push(
                                        <button
                                            key={i}
                                            onClick={() => goToPage(i)}
                                            disabled={loading}
                                            className={i === currentPage ? 'active' : ''}
                                        >
                                            {i + 1}
                                        </button>
                                    );
                                }

                                // Show ellipsis at end if needed
                                if (endPage < totalPages - 1) {
                                    if (endPage < totalPages - 2) {
                                        pageNumbers.push(<span key="ellipsis-end">...</span>);
                                    }
                                    pageNumbers.push(
                                        <button
                                            key={totalPages - 1}
                                            onClick={() => goToPage(totalPages - 1)}
                                            disabled={loading}
                                        >
                                            {totalPages}
                                        </button>
                                    );
                                }

                                return pageNumbers;
                            })()}
                        </div>

                        <button
                            onClick={() => goToPage(page + 1)}
                            disabled={page >= displayMetadata.totalPages - 1 || loading}
                        >
                            Next
                        </button>
                        <button
                            onClick={() => goToPage(displayMetadata.totalPages - 1)}
                            disabled={page >= displayMetadata.totalPages - 1 || loading}
                        >
                            Last
                        </button>
                    </div>

                    <div className="pagination-summary">
                        Showing {page * pageSize + 1} - {Math.min((page + 1) * pageSize, displayMetadata.totalElements)} of {displayMetadata.totalElements.toLocaleString()}
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
                        <button onClick={() => handleSearch(0, true)} className="search-btn-large">
                            Search All Logs
                        </button>
                    </div>
                </div>
            )}

            {searchExecuted && searchResponse && searchResponse.logs.length === 0 && (
                <div className="empty-state">
                    <p>No logs found matching your criteria</p>
                    <span>Try adjusting your filters or expanding the time range</span>
                </div>
            )}
        </div>
    );
}

export default LogSearch;