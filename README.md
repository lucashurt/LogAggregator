# Enterprise Log Aggregation System

A **distributed log aggregation system** built to demonstrate enterprise-scale architecture patterns. Capable of ingesting over 6,000 logs/sec and handling complex search queries during heavy ingestion in under 100 ms.

---

## 🎯 Project Overview

A structured journey from a basic REST API to a fully distributed, production-ready log aggregation platform with real-time monitoring capabilities. Each phase addresses real scalability, reliability, and observability challenges found in enterprise systems.

**Current Status:**
✅ **Weeks 1–7 Complete: Real-Time Streaming & Frontend**
_Full-stack application with WebSocket streaming, React dashboard, Redis caching, and Elasticsearch-powered search._

---

## ⚡ Performance Overview

> **Tested on:** MacBook Air M1 (8GB RAM) using Testcontainers. See [PERFORMANCE.md](./PERFORMANCE.md) for detailed benchmarks and tuning.

### Benchmark Results

#### Capacity Test (Isolated Backend)
| Target Rate | Actual Rate | Efficiency | Status |
|-------------|-------------|------------|--------|
| 100/sec | 105/sec | 100% | ✅ |
| 500/sec | 505/sec | 100% | ✅ |
| 1,000/sec | 1,000/sec | 100% | ✅ |
| 2,000/sec | 2,020/sec | 100% | ✅ |
| 3,000/sec | 3,025/sec | 100% | ✅ |
| **5,000/sec** | **5,025/sec** | **100%** | ✅ |

#### Stability Test (5-Minute Sustained Load)
| Metric | Result |
|--------|--------|
| **Target Rate** | 6,000 logs/sec |
| **Average Throughput** | 6,008 logs/sec |
| **Total Logs Processed** | 722,000 |
| **Data Integrity** | 100.00% ✅ |
| **Search Latency (Under Load)** | 30ms avg |
| **Throughput Stability** | -0.5% degradation ✅ |

#### Elasticsearch vs PostgreSQL (200,000 logs)

| Query Type | PostgreSQL | Elasticsearch | ES Speedup |
|------------|------------|---------------|------------|
| Full-text search (`'timeout'`) | 221ms | 12ms | **18.4x faster** |
| Exact match (`service='payment'`) | 41ms | 5ms | **8.2x faster** |
| Range + filter (12h + ERROR) | 70ms | 12ms | **5.8x faster** |
| Complex (service + level + text) | 43ms | 8ms | **5.4x faster** |
| Aggregation (GROUP BY service) | 244ms | 108ms | **2.3x faster** |
| **Concurrent load (50 users)** | 1,581ms avg | 201ms avg | **7.8x faster** |

**Ingestion rates:** PostgreSQL 19,824 logs/sec · Elasticsearch 10,785 logs/sec

### Key Performance Metrics

| Feature | Performance |
|---------|-------------|
| **Max Backend Capacity** | 5,000+ logs/sec |
| **Sustained Throughput** | 6,000 logs/sec (with 100% integrity) |
| **Search Latency** | 5-12ms (Elasticsearch) |
| **Cache Hit Latency** | < 5ms (Redis) |
| **ES vs PostgreSQL Search** | 8-18x faster |
| **Concurrent Users (50)** | 201ms avg vs 1,581ms (7.8x faster) |

### Production Projections

| Environment | Expected Rate | Notes |
|-------------|---------------|-------|
| **MacBook Air M1 (8GB)** | 6,000 logs/sec | ✅ Verified in tests |
| **Production Server (32GB, NVMe)** | 15,000-20,000 logs/sec | 4x RAM, dedicated resources |
| **AWS m6i.4xlarge (16 vCPU, 64GB)** | 20,000-30,000 logs/sec | 8x RAM, server-grade I/O |
| **Kubernetes Cluster** | 60,000+ logs/sec | Horizontal scaling |

### Architecture Scales Linearly

The system achieved **6,000 logs/sec on an 8GB laptop** with 100% data integrity. Production hardware with 4-8x more RAM and dedicated CPUs will scale proportionally. The bottleneck is hardware, not architecture.

---

## 🏗️ Architecture

```text
                              [ REACT FRONTEND ]
                                     │
                    ┌────────────────┼────────────────┐
                    │                │                │
              Live Stream       Search API      Pause/Resume
              (WebSocket)       (REST)          Controls
                    │                │                │
                    └────────────────┼────────────────┘
                                     │
                              [ SPRING BOOT ]
                                     │
        ┌────────────────────────────┼────────────────────────────┐
        │                            │                            │
        ▼                            ▼                            ▼
   WRITE PATH                   READ PATH                  STREAMING PATH
   (Async)                      (Cached)                   (Real-Time)
        │                            │                            │
   LogController              CachedElasticsearch         WebSocketService
        ▼                            ▼                            │
   LogProducer                 ┌─────────────┐                    │
        ▼                      │    Redis    │◄───────────────────┘
   Kafka Topic                 │   (Cache)   │              broadcastBatch()
   (3 partitions)              └──────┬──────┘                    │
        ▼                          HIT│MISS                       │
   LogConsumer (x3)                   │                           │
        │                             ▼                           │
        ├──────────────► Elasticsearch ◄──────────────────────────┤
        │   (async)      (Aggregations)                           │
        ▼                                                         │
   PostgreSQL ◄───────────────────────────────────────────────────┘
   (sync/ACID)                                              On DB Write
        │
        ▼ On Failure
   Kafka DLQ

  ┌──────────────────────────────────────────────────────────────────┐
  │  INFRASTRUCTURE                                                  │
  │  [ PostgreSQL ]  [ Elasticsearch ]  [ Redis ]  [ Kafka ]         │
  │                                                                  │
  │  MONITORING                                                      │
  │  [ Prometheus ]  [ Grafana ]  [ Custom Metrics ]  [ Actuator ]   │
  └──────────────────────────────────────────────────────────────────┘
```

---

## 🚀 Quick Start

### Prerequisites
- Docker & Docker Compose
- Python 3.8+ (for log generation scripts)
- 8GB RAM (tested and verified at 6,000 logs/sec)

### 1. Start the Application
```bash
# Start all services
docker-compose up --build -d

# Verify all services are healthy
docker-compose ps
```

### 2. Open the Dashboard
Navigate to http://localhost:3000

### 3. Generate Test Data

**Option A: Batch load historical data**
```bash
pip install requests
python generate_logs.py  # Generates 100k logs
```

**Option B: Stream real-time logs**
```bash
python stream_logs.py              # Default: 100 logs/sec (safe)
python stream_logs.py --rate 200   # Higher rate (if your system can handle it)
```

> 💡 **Tip:** Start with `--rate 100` and increase gradually. If your system slows down, reduce the rate.

---

## 📋 System Requirements

### Minimum (Development)
- 8 GB RAM
- 4 CPU cores
- 20 GB disk space
- Docker Desktop with 6GB+ allocated

### Recommended (Testing High Throughput)
- 16 GB RAM
- 8 CPU cores
- SSD storage
- Docker Desktop with 10GB+ allocated

### Docker Resource Allocation

Open Docker Desktop → Settings → Resources:
- **Memory:** 8GB minimum, 12GB+ recommended
- **CPUs:** 4 minimum, 6+ recommended
- **Swap:** 2GB
- **Disk:** 40GB+

---

## 🔧 Configuration & Tuning

### Elasticsearch Refresh Interval

The default 5-second refresh interval is good for real-time visibility. For higher throughput at the cost of search freshness:

```json
// elasticsearch-settings.json
{
  "index": {
    "refresh_interval": "30s"  // Reduces CPU, logs appear in search after 30s
  }
}
```

### WebSocket Throttling

The backend automatically throttles WebSocket broadcasts:
- Max 4 broadcasts/second
- Max 500 logs per broadcast
- 2000 log buffer before dropping

This prevents browser overload during high-throughput scenarios.

---

## 🖥️ Frontend Screenshots

### Live Stream View
- Real-time log feed with 2,500-log buffer
- Color-coded log levels (ERROR=red, WARN=yellow, INFO=blue, DEBUG=gray)
- Pause/Resume with background buffering
- Expandable log details with metadata
  
<img width="700" height="335" alt="Screenshot 2025-12-26 at 10 36 56 AM" src="https://github.com/user-attachments/assets/50d142b1-ad3f-4d78-a730-eed4d61a58f7" />

### Search View
- Time range presets (1h, 6h, 24h, 7d)
- Full-text search with highlighting
- Paginated results with smart navigation
- Aggregation metrics dashboard (level counts, service distribution)
- 
<img width="700" height="335" alt="Screenshot 2025-12-26 at 10 35 56 AM" src="https://github.com/user-attachments/assets/a2c7c9d5-8524-42ac-a772-1a366b00b3c0" />

## 📚 API Documentation

### Ingestion Endpoints

#### POST `/api/v1/logs` - Single Log
```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "serviceId": "auth-service",
  "level": "ERROR",
  "message": "Authentication failed for user",
  "traceId": "trace-abc-123",
  "metadata": { "userId": "12345", "ip": "192.168.1.1" }
}
```

#### POST `/api/v1/logs/batch` - Batch (Recommended)
```json
[
  { "timestamp": "...", "serviceId": "...", ... },
  { "timestamp": "...", "serviceId": "...", ... }
]
```

### Search Endpoint

#### GET `/api/v1/logs/search`
| Parameter | Type | Description |
|-----------|------|-------------|
| `query` | string | Full-text search in message |
| `serviceId` | string | Filter by service |
| `level` | enum | INFO, DEBUG, WARNING, ERROR |
| `startTime` | ISO datetime | Range start |
| `endTime` | ISO datetime | Range end |
| `page` | int | Page number (0-indexed) |
| `size` | int | Results per page (max 1000) |

### WebSocket

- **Endpoint:** `/ws` (STOMP over SockJS)
- **Subscribe:** `/topic/logs-batch`

---

## 🧪 Running Tests

### All Tests
```bash
cd backend
./mvnw test
```

### Load Tests Only
```bash
./mvnw test -Dgroups=load-test
```

### Stability Test
```bash
./mvnw test -Dtest=StabilityTest -Dgroups=stability-test
```

> **Note:** Test results will be higher than real-world performance. See [PERFORMANCE.md](./PERFORMANCE.md) for details.

---

## 🐳 Docker Commands

```bash
# Start all services
docker-compose up --build -d

# View logs
docker-compose logs -f backend

# Check resource usage
docker stats

# Stop all services
docker-compose down

# Clean restart (removes data)
docker-compose down -v && docker-compose up --build -d
```

---

## 📁 Project Structure

```
LogAggregator/
├── backend/                    # Spring Boot application
│   ├── src/main/java/         # Application code
│   └── src/test/java/         # Tests including load tests
├── frontend/                   # React application
│   └── log-viewer/
├── docker-compose.yml          # Full stack orchestration
├── PERFORMANCE.md              # Detailed performance guide
├── generate_logs.py            # Batch data generator
└── stream_logs.py              # Real-time log streamer
```

---

## 🎓 Learning Outcomes

This project demonstrates:

| Concept | Implementation |
|---------|----------------|
| **Async Processing** | Kafka consumers, CompletableFuture |
| **Caching** | Redis Look-Aside pattern |
| **Real-Time Streaming** | WebSocket with STOMP |
| **Polyglot Persistence** | PostgreSQL + Elasticsearch |
| **Performance Testing** | Load testing, capacity planning |
| **Containerization** | Docker Compose orchestration |

---

## 🛣️ Roadmap

| Phase | Status | Description |
|-------|--------|-------------|
| Phase 1 | ✅ | Foundation (REST API, PostgreSQL) |
| Phase 2 | ✅ | Async Processing (Kafka) |
| Phase 3 | ✅ | Monitoring (Prometheus/Grafana) |
| Phase 4 | ✅ | Elasticsearch (Hybrid search) |
| Phase 5 | ✅ | Redis Caching |
| Phase 6 | ✅ | Docker Containerization |
| Phase 7 | ✅ | Real-Time Streaming & Frontend |
| Phase 8 | ⏭️ | Cloud Deployment (AWS/GCP) |
---

## 🔧 Tech Stack

### Backend
- Java 24, Spring Boot 3.4.1
- Apache Kafka, PostgreSQL, Elasticsearch, Redis

### Frontend
- React 18, SockJS/STOMP

### Infrastructure
- Docker Compose, nginx, Prometheus, Grafana

---

## 📄 License

This project is for educational and portfolio purposes.

---

**Built with:** Spring Boot · Kafka · PostgreSQL · Elasticsearch · Redis · React · WebSocket · Docker
