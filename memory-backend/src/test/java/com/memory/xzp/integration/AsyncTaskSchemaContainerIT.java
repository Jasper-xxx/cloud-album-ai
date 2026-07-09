package com.memory.xzp.integration;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
class AsyncTaskSchemaContainerIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("memory_space")
            .withUsername("memory")
            .withPassword("memory");

    @Test
    void asyncTaskSchemaCanBootstrapInsideMysqlContainer() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        )) {
            executeScript(connection, Path.of("src/main/resources/db/migration/V1__create_async_task.sql"));

            try (var statement = connection.createStatement();
                 var resultSet = statement.executeQuery(
                         "SELECT COUNT(*) FROM information_schema.tables "
                                 + "WHERE table_schema = DATABASE() AND table_name = 'async_task'"
                 )) {
                resultSet.next();
                assertEquals(1, resultSet.getInt(1));
            }

            try (var statement = connection.createStatement();
                 var resultSet = statement.executeQuery(
                         "SELECT COUNT(*) FROM information_schema.statistics "
                                 + "WHERE table_schema = DATABASE() "
                                 + "AND table_name = 'async_task' "
                                 + "AND index_name = 'uk_async_task_key'"
                 )) {
                resultSet.next();
                assertEquals(1, resultSet.getInt(1));
            }
        }
    }

    private void executeScript(Connection connection, Path scriptPath) throws Exception {
        String script = Files.readString(scriptPath, StandardCharsets.UTF_8);
        for (String statement : script.split(";")) {
            String sql = statement.trim();
            if (sql.isEmpty()) {
                continue;
            }
            try (var jdbcStatement = connection.createStatement()) {
                jdbcStatement.execute(sql);
            } catch (SQLException ex) {
                if (!isExpectedIdempotencyError(ex)) {
                    throw ex;
                }
            }
        }
    }

    private boolean isExpectedIdempotencyError(SQLException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        return message.contains("Duplicate column name")
                || message.contains("Duplicate key name");
    }
}
