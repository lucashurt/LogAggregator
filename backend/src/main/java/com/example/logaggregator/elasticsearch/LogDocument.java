package com.example.logaggregator.elasticsearch;

import com.example.logaggregator.logs.models.LogStatus;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

import java.time.Instant;
import java.util.Map;


@Data
@Document(indexName = "logs")
@Setting(settingPath = "/elasticsearch-settings.json")
public class LogDocument {
    @Id
    private String id;

    @Field(type = FieldType.Date, format = DateFormat.epoch_millis)
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
