package com.kmarket.paymentservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    /**
     * Throwaway PostgreSQL seeded with the {@code payments} schema used by the service,
     * so tests run without a locally installed database. {@code @ServiceConnection}
     * wires its JDBC url/credentials into the datasource (same pattern as the Orders /
     * Auth services).
     */
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("db/payments-schema.sql"),
                        "/docker-entrypoint-initdb.d/payments-schema.sql");
    }

}

