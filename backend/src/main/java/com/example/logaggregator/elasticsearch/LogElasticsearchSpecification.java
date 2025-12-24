package com.example.logaggregator.elasticsearch;

import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LogElasticsearchSpecification {

    public Criteria buildCriteria(LogSearchRequest request) {
        List<Criteria> criteriaList = new ArrayList<>();

        // Exact match filters
        if (request.serviceId() != null && !request.serviceId().isBlank()) {
            criteriaList.add(Criteria.where("serviceId").is(request.serviceId()));
        }

        if (request.traceId() != null && !request.traceId().isBlank()) {
            criteriaList.add(Criteria.where("traceId").is(request.traceId()));
        }

        if (request.level() != null) {
            // Compare as string since it's stored as keyword
            criteriaList.add(Criteria.where("level").is(request.level()));
        }

        // Time range filter
        if (request.startTimestamp() != null && request.endTimestamp() != null) {
            criteriaList.add(
                    Criteria.where("timestamp")
                            .between(request.startTimestamp(), request.endTimestamp())
            );
        }

        if (request.query() != null && !request.query().isBlank()) {
            // .matches() ensures the query is analyzed (e.g. "Timeout" -> "timeout")
            criteriaList.add(Criteria.where("message").matches(request.query()));
        }

        // If no criteria specified, match all documents
        if (criteriaList.isEmpty()) {
            return new Criteria();
        }

        // Combine all criteria with AND
        Criteria finalCriteria = criteriaList.get(0);
        for (int i = 1; i < criteriaList.size(); i++) {
            finalCriteria = finalCriteria.and(criteriaList.get(i));
        }

        return finalCriteria;
    }
}

