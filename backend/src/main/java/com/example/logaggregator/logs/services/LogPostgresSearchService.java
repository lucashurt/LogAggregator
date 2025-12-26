package com.example.logaggregator.logs.services;

import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import com.example.logaggregator.logs.LogEntrySpecification;
import com.example.logaggregator.logs.LogRepository;
import com.example.logaggregator.logs.models.LogEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
public class LogPostgresSearchService {
    private final LogRepository logRepository;
    private final LogEntrySpecification logEntrySpecification;

    public LogPostgresSearchService(LogRepository logRepository, LogEntrySpecification logEntrySpecification) {
        this.logRepository = logRepository;
        this.logEntrySpecification = logEntrySpecification;
    }

    public void validateTimeRange(Instant startTime, Instant endTime){
        if(startTime != null && endTime != null){

            long daysBetween = Duration.between(startTime, endTime).toDays();
            if(daysBetween > 7){
                throw new IllegalArgumentException(
                        "Time range cannot be greater than 7 days"
                );
            }

            if(startTime.isAfter(endTime)){
                throw new IllegalArgumentException(
                        "Start time cannot be after end time"
                );
            }
        }
    }

    public Page<LogEntry> search(LogSearchRequest request){
        validateTimeRange(request.startTimestamp(), request.endTimestamp());
        Specification<LogEntry> spec = logEntrySpecification.buildSpecification(request);

        Pageable pageable = PageRequest.of(
                request.page(),
                request.size(),
                Sort.by(Sort.Direction.DESC, "timestamp")
        );

        Page<LogEntry> results = logRepository.findAll(spec,pageable);

        return results;
    }
}
