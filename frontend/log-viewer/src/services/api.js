import axios from "axios";

const API_BASE = 'http://localhost:8080/api/v1';

export const searchLogs = async (params) =>{
    const response = await axios.get(`${API_BASE}/logs/search`, { params });
    return response.data;
}

export const ingestLog = async (logEntry) =>{
    const response = await axios.post(`${API_BASE}/logs`, logEntry);
    return response.data;
}

export const ingestLogBatch = async (logs) =>{
    const response = await axios.post(`${API_BASE}/logs/batch`, logs);
    return response.data;

}
