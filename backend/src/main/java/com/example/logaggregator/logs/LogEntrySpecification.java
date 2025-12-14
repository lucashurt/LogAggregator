package com.example.logaggregator.logs;

import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import com.example.logaggregator.logs.models.LogEntry;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Predicate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LogEntrySpecification {
    public Specification<LogEntry> buildSpecification(LogSearchRequest request){
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if(request.serviceId() != null && !request.serviceId().isBlank()){
                predicates.add(criteriaBuilder.equal(root.get("serviceId"),request.serviceId()));
            }

            if(request.traceId() != null && !request.traceId().isBlank()){
                predicates.add(criteriaBuilder.equal(root.get("traceId"),request.traceId()));
            }

            if(request.level() != null){
                predicates.add(criteriaBuilder.equal(root.get("level"),request.level()));
            }

            if (request.startTimestamp() != null && request.endTimestamp() != null) {
                predicates.add(criteriaBuilder.between(
                        root.get("timestamp"),
                        request.startTimestamp(),
                        request.endTimestamp()
                ));
            }

            if(request.query() != null && !request.query().isBlank()){
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("message")),
                        "%" + request.query().toLowerCase() + "%"
                ));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
