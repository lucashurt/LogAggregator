package com.example.logaggregator.elasticsearch;

import com.example.logaggregator.logs.DTOs.LogSearchRequest;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.stereotype.Component;

@Component
public class LogElasticsearchSpecification {

    public Criteria buildCriteria(LogSearchRequest request) {
        Criteria criteria = new Criteria();

        if (request.serviceId() != null && !request.serviceId().isBlank()) {
            criteria = criteria.and(
                    Criteria.where("serviceId").is(request.serviceId())
            );
        }

        if (request.traceId() != null && !request.traceId().isBlank()) {
            criteria = criteria.and(
                    Criteria.where("traceId").is(request.traceId())
            );
        }

        if (request.level() != null) {
            criteria = criteria.and(
                    Criteria.where("level").is(request.level().toString())
            );
        }

        if (request.startTimestamp() != null && request.endTimestamp() != null) {
            criteria = criteria.and(
                    Criteria.where("timestamp")
                            .between(request.startTimestamp(), request.endTimestamp())
            );
        }

        if (request.query() != null && !request.query().isBlank()) {
            criteria = criteria.and(
                    Criteria.where("message").contains(request.query())
            );
        }

        return criteria;
    }
}