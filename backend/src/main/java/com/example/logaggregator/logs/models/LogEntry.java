package com.example.logaggregator.logs.models;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "log_entries", indexes = {
        @Index(name = "idx_timestamp", columnList = "timestamp"),
        @Index(name = "idx_serviceId", columnList = "serviceId"),
        @Index(name = "idx_level", columnList = "level")
})
@Data
public class LogEntry {
    @Id  // <-- ADD THIS
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(nullable = false)
    private String serviceId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private LogStatus level;

    @Column(nullable = false)
    private String message;

    @Column(columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)  // Use this instead of @Type
    private Map<String,Object> metadata;

    @Column(unique = true)
    private String traceId;

    @Column(nullable = false)
    private Instant createdAt;
}
