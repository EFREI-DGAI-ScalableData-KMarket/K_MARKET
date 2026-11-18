package com.kmarket.authservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@SpringBootTest
@Testcontainers
class AuthServiceApplicationTests {

    /**
     * Throwaway PostgreSQL so {@code contextLoads()} runs without a locally installed
     * database. The container is seeded with the {@code auth} schema used by the service;
     * {@code @ServiceConnection} wires its JDBC url/credentials into the datasource.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("db/auth-schema.sql"),
                    "/docker-entrypoint-initdb.d/auth-schema.sql");

    @DynamicPropertySource
    static void jpaProperties(DynamicPropertyRegistry registry) {
        // The production schema is created out-of-band and Hibernate must not touch it.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "auth");
    }

    @Test
    void contextLoads() {
    }

}
