# Enterprise Log Aggregation System

A production-grade distributed log aggregation system built as a structured learning project to understand enterprise-scale system architecture. This project demonstrates how to build systems that handle tens of thousands of logs per second while providing fast search capabilities across millions of entries.

## 🎯 Project Overview

This is a 5-week journey from a simple REST API to a fully distributed log aggregation platform, similar to systems like Datadog, Splunk, or the ELK stack. Each phase introduces specific distributed systems concepts and solves real scalability challenges.

**Current Status:** ✅ **Weeks 1-2 Complete** (MVP with advanced search)

## 🏗️ Architecture Evolution

### Current Architecture (Weeks 1-2)
```
Client → Spring Boot API → PostgreSQL
                ↓
         Search/Query Engine
```

### Target Architecture (Week 12)
```
Clients → Load Balancer → Spring Boot APIs
                              ↓
                          Kafka Queue
                              ↓
                     Batch Processors
                    ↙     ↓      ↘
            PostgreSQL  Elasticsearch  S3
                 ↓           ↓          ↓
              Metadata    Search    Cold Storage
                              ↓
                     WebSocket Server → React Dashboard
                              ↓
                        Redis Cache
```

## 🚀 Current Features (Weeks 1)

### Core Functionality
- **RESTful Log Ingestion API**: Submit individual or batch logs via HTTP
- **Flexible Search Engine**: Filter logs by service, level, trace ID, timestamp range, or text query
- **Production-Grade Database**: PostgreSQL with optimized indexes and JSONB support
- **Pagination**: Efficient result pagination with configurable page sizes
- **Input Validation**: Comprehensive request validation with detailed error messages

### Technical Highlights
- **Repository Pattern**: JPA with dynamic query building using Specifications
- **DTO Layer**: Clean separation between API contracts and domain models
- **Batch Processing**: Optimized bulk ingestion with transaction management
- **Time Range Validation**: Built-in protection against expensive queries (7-day limit)
- **Comprehensive Testing**: Unit tests, integration tests, and load tests

### Performance Metrics (Load Test Results)
- **Batch Ingestion**: 1,000 logs in ~3-5 seconds
- **Search Performance**: <1 second for complex queries across 1,000 logs
- **Concurrent Searches**: Handles 10+ simultaneous search requests
- **Pagination**: Efficient traversal of large result sets

## 📋 Prerequisites

- **Java 17** or higher
- **PostgreSQL 14+** 
- **Maven 3.9+**
- **Git**

## 🔧 Setup Instructions

### 1. Clone the Repository
```bash
git clone <repository-url>
cd LogAggregator
```

### 2. Database Setup

Create a PostgreSQL database:
```sql
CREATE DATABASE log_aggregator;
CREATE USER log_user WITH PASSWORD 'your_password';
GRANT ALL PRIVILEGES ON DATABASE log_aggregator TO log_user;
```

### 3. Configure Application

Copy the example properties file:
```bash
cd backend/src/main/resources
cp application.properties.example application.properties
```

Edit `application.properties`:
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/log_aggregator
spring.datasource.username=log_user
spring.datasource.password=your_password
```

### 4. Build and Run

```bash
cd backend
./mvnw clean install
./mvnw spring-boot:run
```

The API will be available at `http://localhost:8080`

## 📚 API Documentation

### Ingest Single Log
```bash
POST http://localhost:8080/api/v1/logs
Content-Type: application/json

{
  "timestamp": "2025-01-15T10:30:00Z",
  "serviceId": "auth-service",
  "level": "INFO",
  "message": "User logged in successfully",
  "metadata": {
    "userId": "12345",
    "ip": "192.168.1.1"
  },
  "traceId": "trace-abc-123"
}
```

### Ingest Batch Logs
```bash
POST http://localhost:8080/api/v1/logs/batch
Content-Type: application/json

[
  {
    "timestamp": "2025-01-15T10:30:00Z",
    "serviceId": "auth-service",
    "level": "INFO",
    "message": "User logged in",
    "metadata": {"userId": "12345"},
    "traceId": "trace-abc-123"
  },
  {
    "timestamp": "2025-01-15T10:31:00Z",
    "serviceId": "payment-service",
    "level": "ERROR",
    "message": "Payment failed",
    "metadata": {"amount": 99.99},
    "traceId": "trace-abc-124"
  }
]
```

### Search Logs

Search with multiple filters:
```bash
GET http://localhost:8080/api/v1/logs/search?serviceId=auth-service&level=ERROR&page=0&size=50
```

Available query parameters:
- `serviceId` - Filter by service identifier
- `level` - Filter by log level (INFO, DEBUG, WARNING, ERROR)
- `traceId` - Filter by distributed trace ID
- `startTime` - Start of time range (ISO 8601)
- `endTime` - End of time range (ISO 8601)
- `query` - Text search in message field
- `page` - Page number (default: 0)
- `size` - Page size (default: 50, max: 1000)

**Example Response:**
```json
{
  "logs": [
    {
      "id": 1,
      "timestamp": "2025-01-15T10:30:00Z",
      "serviceId": "auth-service",
      "level": "INFO",
      "message": "User logged in successfully",
      "traceId": "trace-abc-123",
      "metadata": {"userId": "12345"},
      "createdAt": "2025-01-15T10:30:01Z"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "currentPage": 0,
  "size": 50
}
```

## 🧪 Running Tests

### Run All Tests
```bash
./mvnw test
```

### Run Load Tests
```bash
./mvnw test -Dtest=LogLoadTest
```

Load tests validate:
- Batch ingestion performance (1,000 logs)
- Individual ingestion throughput
- Search performance across large datasets
- Pagination efficiency
- Concurrent search handling

## 🗂️ Project Structure

```
backend/
├── src/main/java/com/example/logaggregator/
│   ├── config/              # Security and application configuration
│   ├── exception/           # Global exception handling
│   └── logs/
│       ├── DTOs/            # Data Transfer Objects
│       │   ├── LogEntryRequest.java
│       │   ├── LogEntryResponse.java
│       │   ├── LogSearchRequest.java
│       │   └── LogSearchResponse.java
│       ├── models/          # Domain entities
│       │   ├── LogEntry.java
│       │   └── LogStatus.java
│       ├── services/        # Business logic
│       │   ├── LogIngestService.java
│       │   └── LogSearchService.java
│       ├── LogController.java          # REST endpoints
│       ├── LogRepository.java          # Data access
│       └── LogEntrySpecification.java  # Dynamic query builder
└── src/test/java/           # Comprehensive test suite
```

## 🛣️ Development Roadmap

### ✅ Phase 1: Foundation (Weeks 1-2) - COMPLETED
- HTTP REST API for log ingestion
- PostgreSQL storage with indexes
- Flexible search with pagination
- Comprehensive test suite

### 📅 Phase 2: Message Queue (Weeks 2) - NEXT
**Problem Being Solved**: API becomes bottleneck under high load; database writes block request threads

### 📅 Phase 3: Search Optimization (Weeks 3)
**Problem Being Solved**: PostgreSQL full-text search doesn't scale; slow queries on text fields

### 📅 Phase 4: Real-Time Streaming (Weeks 7-8)
**Problem Being Solved**: Users need live log tailing without polling

### 📅 Phase 5: Production Features (Weeks 4)
**Problem Being Solved**: Repeated queries waste resources; old logs consume expensive storage

### 📅 Phase 6: Enterprise Polish (Weeks 5)
**Problem Being Solved**: Multiple teams need isolated environments; prevent abuse

## 📊 Database Schema

```sql
CREATE TABLE log_entries (
    id BIGSERIAL PRIMARY KEY,
    timestamp TIMESTAMP NOT NULL,
    service_id VARCHAR(100) NOT NULL,
    level VARCHAR(20) NOT NULL,
    message TEXT NOT NULL,
    metadata JSONB,
    trace_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    
    INDEX idx_timestamp (timestamp),
    INDEX idx_service_id (service_id),
    INDEX idx_level (level)
);
```

## 🐛 Known Limitations (To Be Addressed)

- No authentication/authorization (Week 11-12)
- Synchronous writes create latency (Week 3-4)
- Full-text search is basic (Week 5-6)
- No caching layer (Week 9-10)
- No observability metrics (Week 9-10)
- No horizontal scalability (Week 11-12)

## 🤝 Contributing

This is a personal learning project, but feedback and suggestions are welcome via issues.

## 📝 License

This project is for educational purposes.

---
