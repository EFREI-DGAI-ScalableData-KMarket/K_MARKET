package com.kmarket.authservice.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Tests connectivity to the K-Market PostgreSQL datasource configured in
 * {@code application.properties} (DB {@code kmarket}, schema {@code auth},
 * role {@code auth_svc}).
 *
 * <p>Exposes {@link #testConnection()} as a reusable function and also runs it
 * once at application startup via {@link CommandLineRunner} so the result is
 * visible in the logs.</p>
 */
@Slf4j
@Component
public class DatabaseConnectionTester implements CommandLineRunner {

    /** Default timeout, in seconds, for the connection validity check. */
    private static final int VALIDATION_TIMEOUT_SECONDS = 5;

    private final DataSource dataSource;

    public DatabaseConnectionTester(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Opens a connection from the pool, validates it and runs a lightweight
     * probe query ({@code SELECT 1}).
     *
     * @return the result of the connection test (never {@code null})
     */
    public ConnectionTestResult testConnection() {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
                return ConnectionTestResult.failure("Connection is not valid", null);
            }

            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                resultSet.next();
            }

            String url = connection.getMetaData().getURL();
            String product = connection.getMetaData().getDatabaseProductName();
            String version = connection.getMetaData().getDatabaseProductVersion();
            String user = connection.getMetaData().getUserName();
            return ConnectionTestResult.success(url, product, version, user);
        } catch (SQLException ex) {
            return ConnectionTestResult.failure(ex.getMessage(), ex);
        }
    }

    @Override
    public void run(String... args) {
        ConnectionTestResult result = testConnection();
        if (result.ok()) {
            log.info("Database connection OK — {} {} as user '{}' at {}",
                    result.databaseProduct(), result.databaseVersion(),
                    result.user(), result.url());
        } else {
            log.error("Database connection FAILED — {}", result.message(), result.cause());
        }
    }

    /**
     * Immutable result of a connection test.
     *
     * @param ok               whether the connection test succeeded
     * @param url              the JDBC URL of the tested connection (success only)
     * @param databaseProduct  the database product name, e.g. {@code PostgreSQL} (success only)
     * @param databaseVersion  the database product version (success only)
     * @param user             the connected user (success only)
     * @param message          a human-readable status message
     * @param cause            the underlying exception on failure, otherwise {@code null}
     */
    public record ConnectionTestResult(
            boolean ok,
            String url,
            String databaseProduct,
            String databaseVersion,
            String user,
            String message,
            Throwable cause) {

        static ConnectionTestResult success(String url, String databaseProduct,
                                            String databaseVersion, String user) {
            return new ConnectionTestResult(true, url, databaseProduct, databaseVersion,
                    user, "Connection successful", null);
        }

        static ConnectionTestResult failure(String message, Throwable cause) {
            return new ConnectionTestResult(false, null, null, null, null, message, cause);
        }
    }
}
