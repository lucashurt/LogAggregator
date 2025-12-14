package com.example.logaggregator.logs;


import com.example.logaggregator.logs.models.LogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface LogRepository extends JpaRepository<LogEntry,Long>, JpaSpecificationExecutor<LogEntry> {
    Optional<LogEntry> findByTraceId(String traceId);
}
