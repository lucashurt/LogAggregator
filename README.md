# Enterprise Log Aggregation System

A **production-grade distributed log aggregation system** built to demonstrate enterprise-scale architecture patterns.  
This system handles **15,000+ logs per second** with async processing, comprehensive monitoring, and resilient failure handling — similar to systems used by **Datadog, Splunk, and Uber**.

---

## 🎯 Project Overview

A structured journey from a basic REST API to a fully distributed, production-ready log aggregation platform.  
Each phase addresses real scalability, reliability, and observability challenges found in enterprise systems.

**Current Status:**  
✅ **Weeks 1–4 Complete + Production Monitoring**  
_Distributed async processing with full observability_

---

## 🏗️ Architecture Evolution

### ✅ Current Architecture (Weeks 1–4 + Monitoring)

Clients → Spring Boot REST API (HTTP 202 Accepted)
↓
Kafka Topic (logs)
/ |
/ |
Consumer Consumer Consumer (3 threads, batch processing)
\ | /
\ | /
PostgreSQL (Batch Insert: 500/transaction)
↓
Health Checks + Metrics (Micrometer/Prometheus)
↓
DLQ Topic (logs-dlq) ← Failed messages

markdown
Copy code

**Key Components**
- **Non-blocking API:** Returns immediately, queues to Kafka
- **Message Queue:** Apache Kafka with partitioning by `serviceId`
- **Batch Consumers:** 3 concurrent threads, 500 messages per poll
- **Dead Letter Queue:** Automatic retry (3 attempts) with failure isolation
- **Production Monitoring:** Custom metrics, health indicators, DLQ tracking

---

### 🎯 Target Architecture (Week 12)

Load Balancer → Spring Boot APIs (Auto-scaled)
↓
Kafka Cluster
↓
Batch Processors (Kubernetes HPA)
↙ ↓ ↘
PostgreSQL Elasticsearch S3
↓ ↓ ↓
Metadata Search Cold Storage
↓
WebSocket Server → React Dashboard
↓
Redis Cache

yaml
Copy code

---

## 🚀 Current Features (Weeks 1–4 + Monitoring)

### Core Functionality
- **Asynchronous Log Ingestion:** Non-blocking HTTP API with Kafka queues
- **High Throughput Processing:** 15,000+ logs/sec with batch operations
- **Resilient Failure Handling:** DLQ with automatic retry (3 attempts, 1s backoff)
- **Advanced Search Engine:** Filter by service, level, trace ID, time range, text
- **Production Monitoring:** Custom business metrics via Prometheus
- **Health Checks:** Kafka & database connectivity
- **Batch Optimization:** Hibernate batch inserts (500 records/transaction)

---

### Technical Highlights
- **Event-Driven Architecture:** Kafka producer/consumer model
- **Consumer Concurrency:** 3 parallel consumers
- **Smart Partitioning:** Partitioning by `serviceId`
- **Batch Processing:** Kafka (`max.poll.records=500`) + DB batching
- **Error Handling:** Circuit breaker pattern + DLQ
- **Observability:** 5 custom Micrometer metrics
- **Testing:** 51 automated tests including load validation

---

## 📊 Production Monitoring Features

### Custom Metrics
- `logs.published.total` – Logs accepted by API
- `logs.consumed.total` – Logs persisted to DB
- `logs.dlq.total` – Failed logs sent to DLQ
- `api.logs.ingest.duration` – API latency
- `consumer.batch.processing.duration` – DB write latency

---

### Health Indicators
- Kafka cluster connectivity (5s timeout)
- Database connection status
- Consumer lag monitoring
- DLQ rate tracking (alerts at >1%)

---

### Monitoring Endpoints
- `/actuator/health`
- `/actuator/prometheus`
- `/actuator/metrics`
- `/api/v1/admin/dlq/status`
- `/api/v1/admin/dlq/metrics`

---

## ⚡ Performance Metrics (Load-Tested)

| Metric | Value |
|------|------|
| API Response Time | ~6ms (async) |
| Processing Throughput | **9,000 logs/sec** |
| Batch Ingestion | 1,000 logs < 10ms |
| Consumer Processing | 4,921 logs/sec per consumer |
| Queueing Throughput | 200,000 logs/sec |
| P95 Response Time | < 100ms |
| Metrics Accuracy | > 99% |
| Improvement vs Sync DB | **6× faster** |

---

## 📋 Prerequisites
- Java 17+
- PostgreSQL 14+
- Apache Kafka 3.0+
- Maven 3.9+
- Git

---

### 1️⃣ Clone Repository
```bash
git clone <repository-url>
cd LogAggregator
```

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

### Search Logs
* **Endpoint:** `GET /api/v1/logs/search`
* **Filters:**
    * `serviceId`
    * `level`
    * `traceId`
    * `startTime` / `endTime`
    * `query`
    * `page`, `size`

---

## 🧪 Running Tests

Run the full test suite (Unit, Component, Load, and Integration).

```bash
./mvnw test
```

**Current Test Coverage (51 Tests):**
* **Unit Tests:** 33
* **Component Tests:** 10
* **Load Tests:** 7
* **Integration Tests:** 1

---

## 🛣️ Development Roadmap

* ✅ **Phase 1:** Foundation
* ✅ **Phase 2:** Async Processing
* ✅ **Phase 3:** Production Monitoring
* ⏭️ **Phase 4:** Elasticsearch Integration
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

**Built with:** `Spring Boot 4.0` · `Apache Kafka` · `PostgreSQL` · `Micrometer` · `Prometheus`
