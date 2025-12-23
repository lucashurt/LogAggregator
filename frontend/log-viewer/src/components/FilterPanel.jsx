function FilterPanel({ filters, setFilters }) {
    const updateFilter = (key,value) => {
        setFilters(prev => ({...prev, [key]: value}));
    }
    return (
        <aside className="filter-panel">
            <h3>Filters</h3>

            <div className="filter-group">
                <label>Service ID</label>
                <input
                    type="text"
                    placeholder="e.g., auth-service"
                    value={filters.serviceId}
                    onChange={(e) => updateFilter('serviceId', e.target.value)}
                />
            </div>

            <div className="filter-group">
                <label>Log Level</label>
                <select
                    value={filters.level}
                    onChange={(e) => updateFilter('level', e.target.value)}
                >
                    <option value="">All Levels</option>
                    <option value="INFO">INFO</option>
                    <option value="DEBUG">DEBUG</option>
                    <option value="WARNING">WARNING</option>
                    <option value="ERROR">ERROR</option>
                </select>
            </div>

            <div className="filter-group">
                <label>Search Text</label>
                <input
                    type="text"
                    placeholder="Search messages..."
                    value={filters.query}
                    onChange={(e) => updateFilter('query', e.target.value)}
                />
            </div>

            <button
                className="clear-filters"
                onClick={() => setFilters({ serviceId: '', level: '', query: '' })}
            >
                Clear Filters
            </button>
        </aside>
    );
}

export default FilterPanel;
