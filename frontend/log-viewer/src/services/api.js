import axios from "axios";


const API_BASE = process.env.NODE_ENV === 'production'
    ? '/api/v1'                          // Docker: nginx proxies /api to backend
    : 'http://localhost:8080/api/v1';    // Dev: direct connection

export const searchLogs = async (params) => {
    const response = await axios.get(`${API_BASE}/logs/search`, { params });
    return response.data;
}

export const ingestLog = async (logEntry) => {
    const response = await axios.post(`${API_BASE}/logs`, logEntry);
    return response.data;
}

/**
 * Ingest a batch of log entries
 * @param {Array} logs - Array of log entries
 * @returns {Promise<Object>}
 */
export const ingestLogBatch = async (logs) => {
    const response = await axios.post(`${API_BASE}/logs/batch`, logs);
    return response.data;
}
