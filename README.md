# Enterprise Log Aggregation System

A **distributed log aggregation system** built to demonstrate enterprise-scale architecture patterns. **(10k+ logs/sec)** 
---

## 🎯 Project Overview

A structured journey from a basic REST API to a fully distributed, production-ready log aggregation platform.
Each phase addresses real scalability, reliability, and observability challenges found in enterprise systems.

**Current Status:**
✅ **Weeks 1–6 Complete: Caching & Extreme Optimization**
_Distributed async processing with Elasticsearch indexing and **Redis Caching** for sub-5ms read latencies._
---

## 🏗️ Architecture Evolution

### ✅ Current Architecture (Async Hybrid Storage)

```text
                                [ CLIENTS ]
                                  │
                    ┌─────────────┴─────────────┐
                    │                           │
              POST /logs                   GET /search
              POST /batch                       │
                    │                           │
                    │      SPRING BOOT          │
                    │                           │
  ┌─────────────────▼─────────┐    ┌────────────▼──────────────┐
  │   WRITE PATH (Async)      │    │   READ PATH (Cached)      │
  │                           │    │                           │
  │  LogController            │    │  LogController            │
  │        ▼                  │    │        ▼                  │
  │  LogProducer              │    │  CachedElasticsearch      │
  │        ▼                  │    │        ▼                  │
  │  Kafka Topic "logs"       │    │  ┌─────────────┐          │
  │  (3 partitions)           │    │  │    Redis    │          │
  │        ▼                  │    │  │   (Cache)   │          │
  │  LogConsumer (x3)         │    │  └──┬──────┬───┘          │
  │        ▼                  │    │     │      │              │
  │  ┌──────┴──────┐          │    │   HIT    MISS             │
  │  │             │          │    │     │      │              │
  │  ▼             ▼          │    │     │      ▼              │
  │PostgreSQL  Elasticsearch  │    │     │  Elasticsearch      │
  │(sync)      (async)        │    │     │  (fallback:Postgres)|
  │  │                        │    │     │      │              │
  │  │ On Failure             │    │     └──────┴──────┐       │
  │  ▼                        │    │                   ▼       │
  │Kafka DLQ                  │    │          LogSearchResponse│
  └───────────────────────────┘    └───────────────────────────┘

  Monitoring: Prometheus, Grafana, Custom Metrics

  [ PostgreSQL ]  [ Elasticsearch ]  [ Redis ]  [ Kafka ]
```

**Key Components**
- **Hybrid Storage:** PostgreSQL for ACID compliance/backup; Elasticsearch for high-speed text search.
- **Caching Layer:** Redis stores frequent search queries, reducing latency from ~60ms to 4ms (P99 < 20ms).
- **Async Ingestion:** `CompletableFuture` implementation decouples Elasticsearch indexing from the critical path, allowing the consumer to process **12,000+ logs/sec**.
- **Optimized Indexing:** Custom `refresh_interval` (30s) and replica settings to minimize I/O overhead during bulk loads.
- **Message Queue:** Apache Kafka with partitioning by `serviceId`.
- **Resiliency:** Dead Letter Queue (DLQ) with automatic retry and failure isolation.

---

## 🚀 Current Features

### Core Functionality
- **High-Performance Ingestion:** Optimized for **10,000+ logs/sec** on single-node hardware.
- **Hybrid Search Engine:**
    - **Structured Search:** PostgreSQL for exact ID/Time lookups.
    - **Full-Text Search:** Elasticsearch for message content, fuzzy matching, and complex aggregations.
- **Concurrency Optimized:** Handles 75+ concurrent heavy search users with sub-30ms latency (vs Postgres 3.7s).
- **Production Monitoring:** Custom business metrics via Prometheus/Grafana.

### Technical Highlights
- **Async "Fire-and-Forget":** Non-blocking Elasticsearch writes ensure Postgres latency doesn't bottleneck throughput.
- **Inverted Indexing:** Switched from SQL `LIKE %...%` scans **O(N)** to Elasticsearch Inverted Index **O(1)**.
- **Batch Processing:** Kafka batch listeners and Spring Data `saveAll` for efficient network usage.
- **Observability:** Metric tracking for `ingest.latency`, `cache.hit_rate`, and `consumer.lag`.

---

## ⚡ Performance Metrics (Final Benchmark)

### 🏆 Search Performance: 500,000 Log Dataset
*Benchmark: 100 Concurrent Users & 500k Records*

| Search Type | PostgreSQL Latency | Elasticsearch Latency | Speedup | Winner |
|:---|:---:|:---:|:---:|:---|
| **Full-Text Search** | 278ms | **14ms** | **19.8x** | 🚀 Elasticsearch |
| **Concurrent Load** | 6,974ms (13s) 🔴 | **277ms** | **25.1x** | 🚀 Elasticsearch |
| **Exact Match** | 66ms | **11ms** | **6.0x** | 🚀 Elasticsearch |
| **Complex Query** | 75ms | **12ms** | **6.25x** | 🚀 Elasticsearch |
| **Aggregations** | 195ms | **79ms** | **2.5x** | 🚀 Elasticsearch |

### ⚡ System Capacity
| Metric | Value | Notes |
|------|------|-------|
| **Ingestion Rate** | **10,800 logs/sec** | ~930 Million logs/day theoretical max |
| **Write Speedup** | **3.42x** | Compared to direct DB writes |
| **Resilience** | **High** | Survived load that crashed the primary DB |

### Caching performance

| Metric | Uncached (Elasticsearch Direct) | Cached (Redis Hit) | Speedup Factor |
| :--- | :---: | :---: | :---: |
| **Average Latency** | 63.85 ms | **4.15 ms** | **15.4x 🚀** |
| **P50 (Median)** | ~45.00 ms | **3.04 ms** | **14.8x** |
| **P95 (Tail)** | ~98.00 ms | **10.24 ms** | **9.5x** |
| **P99 (Worst Case)** | ~120.00 ms | **17.19 ms** | **7.0x** |

---

## 📋 Prerequisites
- Java 17+ (Running on Java 24 in dev)
- Maven 3.9+
- Docker (Required for infrastructure)
---

## 🛠️ Setup & Installation

The entire project infrastructure is containerized. You do not need to install Postgres, Kafka, or Redis manually.

### 1️⃣ Clone Repository
```bash
git clone <repository-url>
cd LogAggregator
cp .env-example .env
```

### 2️⃣ Start Infrastructure (Docker)
This command builds the backend application and starts all services (Postgres, Kafka, Zookeeper, Elasticsearch, Redis).

```bash
docker-compose up --build
```

### 3️⃣ Access the Application
- **API URL:** http://localhost:8080
- **Actuator Health:** `http://localhost:8080/actuator/health`
---

## 📚 API Documentation

### Ingest Single Log
* **Endpoint:** `POST /api/v1/logs`
* **Response:** `202 Accepted` (Async)

### Ingest Batch Logs
* **Endpoint:** `POST /api/v1/logs/batch`
* **Response:** `202 Accepted`

### Search Logs (Hybrid High-Availability)
* **Endpoint:** `GET /api/v1/logs/search`
* **Behavior:** Checks Redis -> Hits Elasticsearch -> Updates Redis.
* **Params:** `query` (text), `serviceId`, `level`, `startTime`, `endTime`.---

## 🧪 Running Tests

Run the full test suite (Unit, Component, Load, and Integration).

```bash
cd backend
DB_PORT=5433 ./mvnw test
```

**Current Test Coverage (67 Tests):
* **Unit Tests:** 45
* **Component Tests:** 10
* **Load Tests:** 6 (Includes Redis & Elastic Load Benchmarks)
* **Integration Tests:** 7

---

## 🛣️ Development Roadmap

* ✅ **Phase 1:** Foundation
* ✅ **Phase 2:** Async Processing
* ✅ **Phase 3:** Production Monitoring
* ✅ **Phase 4:** Elasticsearch Integration
* ✅ **Phase 5:** Redis Caching
* ✅ **Phase 6:** Docker Containerization
* ⏭️ **Phase 7:** Real-Time Streaming
* ⏭️ **Phase 8:** Cloud Deployment

---

## 🎓 Learning Outcomes

This project demonstrates core concepts in backend engineering:
* **Async Systems:** Decoupling write paths to maximize throughput.
* **Caching Strategy:** Implementing Look-Aside caching to protect expensive search engines.
* **Reliability:** Implementing DLQs and fallback strategies.
* **Benchmarking:** How to properly stress-test a system to find bottlenecks (e.g., Connection Pool limits vs Non-blocking IO).
* **Polyglot Persistence:** Using SQL for truth and NoSQL for search.
* **Containerization:** Managing complex distributed systems with Docker Compose.

**Built with:** `Spring Boot 3.4.1` · `Apache Kafka` · `PostgreSQL` · `Micrometer` · `Prometheus` · `Redis` · `Docker`
