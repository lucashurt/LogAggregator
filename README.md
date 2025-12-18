# Enterprise Log Aggregation System

A **production-grade distributed log aggregation system** built to demonstrate enterprise-scale architecture patterns.
This system handles **high-throughput ingestion (12k+ logs/sec)** with async processing, comprehensive monitoring, and a **Hybrid Storage Architecture** (PostgreSQL + Elasticsearch) to balance reliability with sub-millisecond search at scale.

---

## 🎯 Project Overview

A structured journey from a basic REST API to a fully distributed, production-ready log aggregation platform.
Each phase addresses real scalability, reliability, and observability challenges found in enterprise systems.

**Current Status:**
✅ **Weeks 1–5 Complete: Extreme Scale & Optimization**
_Distributed async processing with optimized Elasticsearch indexing and non-blocking consumers._

---

## 🏗️ Architecture Evolution

### ✅ Current Architecture (Async Hybrid Storage)

Clients →  Spring Boot REST API (HTTP 202 Accepted)
              ⬇
          Kafka Topic (logs)
              ⬇
      3 Consumer Threads (Batch Processors)
              ⬇
    ┌───────────────────────────────┐
    │                               │
    ▼                               ▼
PostgreSQL (Reliability)       Elasticsearch (Speed)
(Synchronous Write)            (Async Fire-and-Forget)
    │                               │
    └──────────────┬────────────────┘
                   ▼
          Health Checks + Metrics
                   ▼
          DLQ Topic (logs-dlq) (Failure Handling)

**Key Components**
- **Hybrid Storage:** PostgreSQL for ACID compliance/backup; Elasticsearch for high-speed text search.
- **Async Ingestion:** `CompletableFuture` implementation decouples Elasticsearch indexing from the critical path, allowing the consumer to process **12,000+ logs/sec**.
- **Optimized Indexing:** Custom `refresh_interval` (30s) and replica settings to minimize I/O overhead during bulk loads.
- **Message Queue:** Apache Kafka with partitioning by `serviceId`.
- **Resiliency:** Dead Letter Queue (DLQ) with automatic retry and failure isolation.

---

## 🚀 Current Features

### Core Functionality
- **High-Performance Ingestion:** Optimized for **12,000+ logs/sec** on single-node hardware.
- **Hybrid Search Engine:**
    - **Structured Search:** PostgreSQL for exact ID/Time lookups.
    - **Full-Text Search:** Elasticsearch for message content, fuzzy matching, and complex aggregations.
- **Concurrency Optimized:** Handles 75+ concurrent heavy search users with sub-30ms latency (vs Postgres 3.7s).
- **Production Monitoring:** Custom business metrics via Prometheus/Grafana.

### Technical Highlights
- **Async "Fire-and-Forget":** Non-blocking Elasticsearch writes ensure Postgres latency doesn't bottleneck throughput.
- **Inverted Indexing:** Switched from SQL `LIKE %...%` scans ($O(N)$) to Elasticsearch Inverted Index ($O(1)$).
- **Batch Processing:** Kafka batch listeners and Spring Data `saveAll` for efficient network usage.
- **Observability:** Metric tracking for `ingest.latency`, `elasticsearch.indexing.time`, and `consumer.lag`.

---

## ⚡ Performance Metrics (Latest Benchmark)

### 🏆 Search Performance: High-Concurrency Stress Test
*Benchmark: 100 Concurrent Users searching 500,000 logs*

| Search Type | PostgreSQL Latency | Elasticsearch Latency | Speedup | Winner |
|:---|:---:|:---:|:---:|:---|
| **Concurrent Heavy Load** | 10,356ms (10s) ⚠️ | **39ms** | **263x** | 🚀 Elasticsearch |
| **Complex Query** (Text + Filter) | 299ms | **20ms** | **15x** | 🚀 Elasticsearch |
| **Exact Match** | 384ms | **18ms** | **21x** | 🚀 Elasticsearch |
| **Full-Text Search** | 596ms | **288ms** | **2.07x** | 🚀 Elasticsearch |

> **Note:** Under concurrent load, PostgreSQL exhausted its connection pool, resulting in 10-second wait times. Elasticsearch handled the same load with sub-50ms response times, proving the necessity of the hybrid architecture.

### ⚡ System Throughput
| Metric | Value | Notes |
|------|------|-------|
| **Ingestion Throughput** | **12,686 logs/sec** | ~1 Billion logs/day capacity |
| PostgreSQL Write Speed | 2,979 logs/sec | Bottleneck (Acid Compliance) |
| Batch Write Time (500k logs) | 39.4 seconds | Full end-to-end processing |
| API Response Time | ~6ms | Non-blocking (Kafka ACK) |

---

## 📋 Prerequisites
- Java 17+ (Running on Java 24 in dev)
- PostgreSQL 14+
- Elasticsearch 8.x or 9.x
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
* **Architecture:**
    * **Primary:** All queries hit **Elasticsearch** first for maximum performance.
    * **Fallback:** If Elasticsearch is unreachable, the system automatically degrades gracefully and routes queries to **PostgreSQL** to ensure data accessibility.
* **Params:** `query` (text), `serviceId`, `level`, `startTime`, `endTime`.---

## 🧪 Running Tests

Run the full test suite (Unit, Component, Load, and Integration).

```bash
./mvnw test
```

**Current Test Coverage (67 Tests):
* **Unit Tests:** 45
* **Component Tests:** 10
* **Load Tests:** 5
* **Integration Tests:** 7

---

## 🛣️ Development Roadmap

* ✅ **Phase 1:** Foundation
* ✅ **Phase 2:** Async Processing
* ✅ **Phase 3:** Production Monitoring
* ✅ **Phase 4:** Elasticsearch Integration
* ⏭️ **Phase 5:** Redis Caching
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
* **Database Tuning:** Understanding refresh_interval, connection pooling, and batch sizes.
* **Reliability:** Implementing DLQs and fallback strategies.
* **Benchmarking:** How to properly stress-test a system to find bottlenecks (e.g., Connection Pool limits vs Non-blocking IO).
* **Polyglot Persistence:** Using SQL for truth and NoSQL for search.

**Built with:** `Spring Boot 4.0` · `Apache Kafka` · `PostgreSQL` · `Micrometer` · `Prometheus`
