# Enterprise Log Aggregation System

A **production-grade distributed log aggregation system** built to demonstrate enterprise-scale architecture patterns.
This system handles **high-throughput ingestion** with async processing, comprehensive monitoring, and a **Hybrid Storage Architecture** (PostgreSQL + Elasticsearch) to balance reliability with high-speed search.

---

## 🎯 Project Overview

A structured journey from a basic REST API to a fully distributed, production-ready log aggregation platform.
Each phase addresses real scalability, reliability, and observability challenges found in enterprise systems.

**Current Status:**
✅ **Weeks 1–5 Complete: Hybrid Storage & Search Engine**
_Distributed async processing with full observability and high-performance search_

---

## 🏗️ Architecture Evolution

### ✅ Current Architecture (Hybrid Storage)

Clients →  Spring Boot REST API (HTTP 202 Accepted)
              ⬇
         Kafka Topic (logs)
              ⬇
      3 Consumer Threads (Batch Processors)
              ⬇
    ┌───────────────────────────────┐
    │                               │
    ▼                               ▼
PostgreSQL (Reliability)      Elasticsearch (Speed)
(Batch Insert: 500/tx)        (Bulk Index: 10k chunks)
    │                               │
    └──────────────┬────────────────┘
                   ▼
          Health Checks + Metrics
                   ▼
          DLQ Topic (logs-dlq) (Failure Handling)

**Key Components**
- **Hybrid Storage:** PostgreSQL for ACID compliance/backup; Elasticsearch for high-speed text search.
- **Message Queue:** Apache Kafka with partitioning by `serviceId`.
- **Batch Consumers:** Parallel consumption writing to both stores efficiently.
- **Smart Ingestion:** Implements **Chunking Strategy** (10k records) to prevent HTTP 413 Payload errors during bulk indexing.
- **Dead Letter Queue:** Automatic retry (3 attempts) with failure isolation.

---

### 🎯 Target Architecture (Final Vision)

Load Balancer → Auto-scaled APIs -> Kafka Cluster -> Kubernetes HPA Consumers -> [Postgres + Elasticsearch] -> Redis Cache -> WebSocket Server → React Dashboard

---

## 🚀 Current Features

### Core Functionality
- **Asynchronous Log Ingestion:** Non-blocking HTTP API with Kafka queues.
- **Hybrid Search Engine:**
    - **Structured Search:** PostgreSQL for exact ID/Time lookups.
    - **Full-Text Search:** Elasticsearch for message content and fuzzy matching.
- **High Throughput:** Optimized for 15,000+ logs/sec.
- **Resilient Failure Handling:** DLQ with automatic retry and error isolation.
- **Production Monitoring:** Custom business metrics via Prometheus/Grafana.

### Technical Highlights
- **Inverted Indexing:** Switched from SQL `LIKE %...%` scans ($O(N)$) to Elasticsearch Inverted Index ($O(1)$).
- **Bulk Ingestion:** Custom chunking logic to handle massive payloads (250k+ logs) without memory overflows.
- **Event-Driven:** Kafka producer/consumer model decoupled from API.
- **Observability:** Metric tracking for `ingest.latency`, `elasticsearch.indexing.time`, and `consumer.lag`.

---

## ⚡ Performance Metrics (Latest Benchmark)

### 🏆 Search Performance: PostgreSQL vs. Elasticsearch
*Benchmark run on 250,000 logs*

| Search Type | Query | PostgreSQL Time | Elasticsearch Time | Speedup | Winner |
|:---|:---|:---:|:---:|:---:|:---|
| **Full-Text** | "connection timeout error" | 279ms | **47ms** | **~6x** | 🚀 Elasticsearch |
| **Complex** | Service + Time Range | 34ms | 67ms | 0.5x | 🐘 PostgreSQL |
| **Fuzzy** | Typos ("tmeout") | ❌ 0 results | ✅ Found results | N/A | 🚀 Elasticsearch |

> **Insight:** As data volume grows linearly, PostgreSQL search time degrades linearly. Elasticsearch search time remains near-constant due to the Inverted Index architecture.

### ⚡ Log Ingestion at Scale
| Metric | Value |
|------|------|
| API Response Time | ~6ms (async) |
| Elasticsearch Write Speed | **~4,000 logs/sec** |
| PostgreSQL Write Speed | ~2,100 logs/sec |
| Queueing Throughput | 200,000 logs/sec |
| P95 Response Time | < 100ms |

---

## 📋 Prerequisites
- Java 17+
- PostgreSQL 14+
- Elasticsearch 8.x or 9.x
- Apache Kafka 3.0+
- Maven 3.9+
- Docker (Recommended for Elasticsearch)

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
* Distributed systems & asynchronous processing
* Performance optimization & database batching
* Reliability engineering (Dead Letter Queues, Retries)
* Observability & monitoring (Micrometer, Prometheus)
* Load & performance testing
* Production system design
* Search Engines: Understanding Inverted Indexes vs B-Trees.
* Bulk Indexing: Handling HTTP 413 Payload limits via Chunking.
* Polyglot Persistence: Using the right database for the right job (SQL for reliability, NoSQL for search).
* Load Testing: Proving linear vs constant time scalability.

**Built with:** `Spring Boot 4.0` · `Apache Kafka` · `PostgreSQL` · `Micrometer` · `Prometheus`
