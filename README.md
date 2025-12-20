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
- **Inverted Indexing:** Switched from SQL `LIKE %...%` scans ($O(N)$) to Elasticsearch Inverted Index ($O(1)$).
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

---

## 📋 Prerequisites
- Java 17+ (Running on Java 24 in dev)
- PostgreSQL 14+
- Elasticsearch 8.x+
- Redis 7.x
- Apache Kafka 3.0+
- Maven 3.9+
- Docker (Recommended for infrastructure)

---

### 1️⃣ Clone Repository
```bash
git clone <repository-url>
cd LogAggregator

### 2️⃣ Database Setup (PostgreSQL)
Execute the following SQL commands to create the database and user:
```sql
CREATE DATABASE log_aggregator;
CREATE USER log_user WITH PASSWORD 'your_password';
GRANT ALL PRIVILEGES ON DATABASE log_aggregator TO log_user;
```

### 3️⃣ Kafka Setup
You can run Kafka locally. **Docker Compose support is coming soon.**

**Option A: Local Kafka**
```bash
# Start Zookeeper
bin/zookeeper-server-start.sh config/zookeeper.properties

# Start Kafka
bin/kafka-server-start.sh config/server.properties

# Create 'logs' topic (3 partitions for concurrency)
bin/kafka-topics.sh --create \
  --topic logs \
  --bootstrap-server localhost:9092 \
  --partitions 3 \
  --replication-factor 1

# Create 'logs-dlq' topic (Dead Letter Queue)
bin/kafka-topics.sh --create \
  --topic logs-dlq \
  --bootstrap-server localhost:9092 \
  --partitions 1 \
  --replication-factor 1
```

### 4️⃣ Configure Application
Set up your environment properties.
```bash
cd backend/src/main/resources
cp application.properties.example application.properties
```

**`application.properties` configuration:**
```properties
# Database
spring.datasource.url=jdbc:postgresql://localhost:5432/log_aggregator
spring.datasource.username=log_user
spring.datasource.password=your_password

# Kafka
spring.kafka.bootstrap-servers=localhost:9092

# Batch Optimization
spring.jpa.properties.hibernate.jdbc.batch_size=500
spring.kafka.consumer.max-poll-records=500
spring.kafka.listener.concurrency=3

# Monitoring
management.endpoints.web.exposure.include=health,info,prometheus,metrics
management.endpoint.health.show-details=always
```

### 5️⃣ Build & Run
```bash
cd backend
./mvnw clean install
./mvnw spring-boot:run
```
**API URL:** `http://localhost:8080`

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
./mvnw test
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
* ⏭️ **Phase 6:** Real-Time Streaming
* ⏭️ **Phase 7:** Cloud Deployment

---

## 🐛 Known Limitations

* **Scalability:** PostgreSQL scalability limits (Phase 4 will address this).
* **Performance:** No caching layer implemented yet.
* **Streaming:** No real-time streaming capabilities.
* **Deployment:** No cloud deployment configurations.
* **Security:** No authentication or multi-tenancy support.

---

## 🎓 Learning Outcomes

This project demonstrates core concepts in backend engineering:
* **Async Systems:** Decoupling write paths to maximize throughput.
* **Caching Strategy:** Implementing Look-Aside caching to protect expensive search engines.
* **Reliability:** Implementing DLQs and fallback strategies.
* **Benchmarking:** How to properly stress-test a system to find bottlenecks (e.g., Connection Pool limits vs Non-blocking IO).
* **Polyglot Persistence:** Using SQL for truth and NoSQL for search.

**Built with:** `Spring Boot 3.4.1` · `Apache Kafka` · `PostgreSQL` · `Micrometer` · `Prometheus` · `Redis`
