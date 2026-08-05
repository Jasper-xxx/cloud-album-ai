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
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class AgentPendingActionSchemaContainerIT {

    private static final long USER_ID = 42L;
    private static final String PENDING_ID = "00000000-0000-0000-0000-000000000042";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("memory_space")
            .withUsername("memory")
            .withPassword("memory");

    @Test
    void migrationBootstrapsAndOnlyOneConcurrentClaimSucceeds() throws Exception {
        try (Connection connection = connection()) {
            connection.createStatement().execute("""
                    CREATE TABLE `user` (
                        `id` BIGINT NOT NULL,
                        PRIMARY KEY (`id`)
                    ) ENGINE=InnoDB
                    """);
            connection.createStatement().execute("INSERT INTO `user` (`id`) VALUES (" + USER_ID + ")");
            connection.createStatement().execute(
                    Files.readString(
                            Path.of("src/main/resources/db/migration/V6__create_agent_pending_action.sql"),
                            StandardCharsets.UTF_8
                    )
            );

            try (var result = connection.createStatement().executeQuery("""
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'agent_pending_action'
                      AND column_name IN (
                          'confirmation_token_hash',
                          'idempotency_key_hash',
                          'actual_affected_file_count',
                          'actual_created_album_count'
                      )
                    """)) {
                assertTrue(result.next());
                assertEquals(4, result.getInt(1));
            }

            try (var result = connection.createStatement().executeQuery("""
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'agent_pending_action'
                      AND column_name = 'payload_json'
                    """)) {
                assertTrue(result.next());
                assertEquals("longtext", result.getString(1));
            }

            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO agent_pending_action (
                        pending_action_id, user_id, family, action,
                        payload_json, payload_hash,
                        confirmation_token_hash, idempotency_key_hash,
                        status, workflow_version,
                        preview_affected_file_count, preview_created_album_count,
                        summary, expires_at
                    ) VALUES (?, ?, 'album', 'create_album', ?, ?, ?, ?, 'PREVIEWED', 'test', 0, 1, ?, ?)
                    """)) {
                insert.setString(1, PENDING_ID);
                insert.setLong(2, USER_ID);
                insert.setString(3, "{\"family\":\"album\",\"action\":\"create_album\"}");
                insert.setString(4, "a".repeat(64));
                insert.setString(5, "b".repeat(64));
                insert.setString(6, "c".repeat(64));
                insert.setString(7, "create album");
                insert.setTimestamp(8, Timestamp.from(Instant.now().plusSeconds(300)));
                assertEquals(1, insert.executeUpdate());
            }

            try (var result = connection.createStatement().executeQuery("""
                    SELECT payload_json
                    FROM agent_pending_action
                    WHERE pending_action_id = '00000000-0000-0000-0000-000000000042'
                    """)) {
                assertTrue(result.next());
                assertEquals(
                        "{\"family\":\"album\",\"action\":\"create_album\"}",
                        result.getString(1),
                        "payload_json must preserve the exact bytes protected by payload_hash"
                );
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> claims = List.of(
                    executor.submit(() -> claim(ready, start)),
                    executor.submit(() -> claim(ready, start))
            );
            ready.await();
            start.countDown();

            int updatedRows = claims.get(0).get() + claims.get(1).get();
            assertEquals(1, updatedRows, "Only one request may atomically claim a preview");
        } finally {
            executor.shutdownNow();
        }

        try (Connection connection = connection();
             PreparedStatement status = connection.prepareStatement("""
                     SELECT status, confirmed_at, started_at
                     FROM agent_pending_action
                     WHERE pending_action_id = ?
                     """)) {
            status.setString(1, PENDING_ID);
            try (var result = status.executeQuery()) {
                assertTrue(result.next());
                assertEquals("EXECUTING", result.getString("status"));
                assertTrue(result.getTimestamp("confirmed_at") != null);
                assertTrue(result.getTimestamp("started_at") != null);
            }
        }
    }

    private int claim(CountDownLatch ready, CountDownLatch start) throws Exception {
        try (Connection connection = connection();
             PreparedStatement claim = connection.prepareStatement("""
                     UPDATE agent_pending_action
                     SET status = 'EXECUTING',
                         confirmed_at = NOW(3),
                         started_at = NOW(3),
                         last_error = NULL,
                         update_time = NOW(3)
                     WHERE pending_action_id = ?
                       AND user_id = ?
                       AND status = 'PREVIEWED'
                       AND expires_at > NOW(3)
                     """)) {
            claim.setString(1, PENDING_ID);
            claim.setLong(2, USER_ID);
            ready.countDown();
            start.await();
            return claim.executeUpdate();
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
    }
}
