package com.example.logaggregator.logs;


import com.example.logaggregator.logs.models.LogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LogRepository extends JpaRepository<LogEntry,Long> {
    Optional<LogEntry> findByTraceId(String traceId);
}
