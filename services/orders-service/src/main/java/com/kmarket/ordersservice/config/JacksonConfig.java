package com.kmarket.ordersservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a shared {@link ObjectMapper} for serializing order events into the outbox
 * payload. Registers {@link JavaTimeModule} so {@code OffsetDateTime} fields serialize
 * as ISO-8601 strings (not numeric timestamps), matching the platform event convention.
 * Declared {@code @ConditionalOnMissingBean} so Spring Boot's auto-configured mapper is
 * used when present.
 */
@Configuration
public class JacksonConfig {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
