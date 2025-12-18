package com.example.logaggregator.elasticsearch;

import com.example.logaggregator.logs.models.LogStatus;
import jakarta.persistence.Id;
import lombok.Data;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;
import java.util.Map;


@Data
@Document(indexName = "logs")
public class LogDocument {
    @Id
    private String id;

    @Field(type= FieldType.Date)
    private Instant timestamp;

    @Field(type = FieldType.Keyword)
    private String serviceId;

    @Field(type = FieldType.Keyword)
    private LogStatus level;

    @Field(type = FieldType.Text, analyzer = "standard") // Full-text search enabled
    private String message;

    @Field(type = FieldType.Object)
    private Map<String, Object> metadata;

    @Field(type = FieldType.Keyword)
    private String traceId;

    @Field(type = FieldType.Date)
    private Instant createdAt;

    @Field(type = FieldType.Long)
    private Long postgresId;
}
