package com.example.logaggregator.config;


import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@EnableCaching
public class RedisConfig {

    @Bean
    public ObjectMapper redisObjectMapper() {
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .build();

        return JsonMapper.builder()
                .configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                // CHANGE HERE: Use DefaultTyping.NON_FINAL instead of ObjectMapper.DefaultTyping.NON_FINAL
                .activateDefaultTyping(typeValidator, DefaultTyping.NON_FINAL)
                .build();
    }
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory redisConnectionFactory,
            ObjectMapper redisObjectMapper) {

        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory);

        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();
        GenericJacksonJsonRedisSerializer jsonSerializer =
                new GenericJacksonJsonRedisSerializer(redisObjectMapper);

        redisTemplate.setKeySerializer(stringRedisSerializer);
        redisTemplate.setValueSerializer(jsonSerializer);
        redisTemplate.setHashKeySerializer(stringRedisSerializer);
        redisTemplate.setHashValueSerializer(jsonSerializer);
        redisTemplate.afterPropertiesSet();

        return redisTemplate;
    }

    @Bean
    public CacheManager cacheManager(
            RedisConnectionFactory redisConnectionFactory,
            ObjectMapper redisObjectMapper) {

        GenericJacksonJsonRedisSerializer jsonSerializer =
                new GenericJacksonJsonRedisSerializer(redisObjectMapper);

        RedisCacheConfiguration redisCacheConfiguration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()
                        )
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer)
                )
                .disableCachingNullValues();

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(redisCacheConfiguration)
                .build();
    }
}
