import {searchLogs} from "../services/api";
import {useState} from "react";
import LogEntry from "./LogEntry";


function LogSearch({ filters }) {
    const [results, setResults] = useState([]);
    const [loading, setLoading] = useState(false);
    const [page, setPage] = useState(0)
    const [totalPages, setTotalPages] = useState(0);

    const handleSearch = async() => {
        setLoading(true);
        try{
            const data = await searchLogs({
                ...filters,
                    page,
                    size:50
            });
            setResults(data.logs);
            setTotalPages(data.totalPages);
        }
        catch (error){
            console.error('Search failed:', error);
        }
        finally{
            setLoading(false);
        }
    };
    return (
        <div className="log-search">
            <div className="search-header">
                <h2>Search Historical Logs</h2>
                <button onClick={handleSearch} disabled={loading}>
                    {loading ? 'Searching...' : 'Search'}
                </button>
            </div>

            {results.length > 0 && (
                <>
                    <div className="log-list">
                        {results.map(log => (
                            <LogEntry key={log.id} log={log} />
                        ))}
                    </div>

                    <div className="pagination">
                        <button
                            onClick={() => setPage(p => Math.max(0, p - 1))}
                            disabled={page === 0}
                        >
                            Previous
                        </button>
                        <span>Page {page + 1} of {totalPages}</span>
                        <button
                            onClick={() => setPage(p => p + 1)}
                            disabled={page >= totalPages - 1}
                        >
                            Next
                        </button>
                    </div>
                </>
            )}
        </div>
    );
}

export default LogSearch;
