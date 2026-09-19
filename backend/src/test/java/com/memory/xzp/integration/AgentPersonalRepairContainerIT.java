package com.memory.xzp.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.xzp.mapper.AgentResourceGrantMapper;
import com.memory.xzp.mapper.UserFileMapper;
import com.memory.xzp.service.AgentResourceGrantService;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.file.*;
import java.sql.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class AgentPersonalRepairContainerIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("personal_repair_test").withUsername("test").withPassword("test");

    @Test void migrationsIsolationAndRollbackUseRealMysql() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute("CREATE TABLE user(id BIGINT PRIMARY KEY)");
            c.createStatement().execute("INSERT INTO user VALUES(1),(2)");
            for (String migration : List.of("V6__create_agent_pending_action.sql", "V9__agent_conversation.sql", "V10__agent_resource_grant.sql", "V11__file_object_gc.sql", "V12__agent_discovery_job.sql")) {
                String script = Files.readString(Path.of("src/main/resources/db/migration", migration));
                for (String sql : script.split(";")) if (!sql.isBlank()) c.createStatement().execute(sql);
            }
            c.createStatement().execute("CREATE TABLE user_file(id BIGINT PRIMARY KEY,user_id BIGINT,file_id VARCHAR(32),is_deleted BOOLEAN,deleted_time DATETIME(3))");
            c.createStatement().execute("INSERT INTO user_file VALUES(1,1,'shared',1,NOW()-INTERVAL 31 DAY),(2,2,'shared',1,NOW()-INTERVAL 1 DAY),(3,1,'active',0,NULL)");
            String deleteSql = UserFileMapper.class.getMethod("deleteDeletedRelation", Long.class, Long.class, java.time.LocalDateTime.class, java.time.LocalDateTime.class)
                    .getAnnotation(Delete.class).value()[0]
                    .replace("#{id}", "?").replace("#{userId}", "?").replace("#{deletedTime}", "?").replace("#{cutoff}", "?");
            Timestamp deletedAt;
            try (ResultSet rs = c.createStatement().executeQuery("SELECT deleted_time FROM user_file WHERE id=1")) { rs.next(); deletedAt = rs.getTimestamp(1); }
            try (PreparedStatement delete = c.prepareStatement(deleteSql)) {
                delete.setLong(1,1); delete.setLong(2,1); delete.setTimestamp(3,deletedAt);
                delete.setTimestamp(4, Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(30)));
                // A recovery after candidate discovery wins: the exact mapper predicate removes nothing.
                try (Connection recovery = ds.getConnection()) { recovery.createStatement().execute("UPDATE user_file SET is_deleted=0,deleted_time=NULL WHERE id=1"); }
                assertEquals(0, delete.executeUpdate());
                try (PreparedStatement reset = c.prepareStatement("UPDATE user_file SET is_deleted=1,deleted_time=? WHERE id=1")) {
                    reset.setTimestamp(1,deletedAt); reset.executeUpdate();
                }
                assertEquals(1, delete.executeUpdate());
            }
            try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM user_file WHERE id IN (2,3)")) { rs.next(); assertEquals(2,rs.getInt(1)); }
        }

        Configuration config = new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(AgentResourceGrantMapper.class);
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean(); bean.setDataSource(ds); bean.setConfiguration(config);
        SqlSessionFactory factory = bean.getObject();
        AgentResourceGrantMapper mapper = new SqlSessionTemplate(factory).getMapper(AgentResourceGrantMapper.class);
        AgentResourceGrantService grants = new AgentResourceGrantService(mapper, new ObjectMapper());
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        AtomicReference<String> rolledBack = new AtomicReference<>();
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            rolledBack.set(grants.issue(1L, List.of("owned-photo"), "share", 60));
            assertNotNull(grants.require(rolledBack.get(), "share"));
            throw new IllegalStateException("Injected pending-result database failure after issuing resource");
        }));
        assertThrows(com.memory.xzp.exception.BusinessException.class, () -> grants.require(rolledBack.get(), "share"));
        String committed = tx.execute(status -> grants.issue(1L, List.of("owned-photo"), "share", 60));
        assertEquals(1L, grants.require(committed, "share").getUserId());
        assertThrows(com.memory.xzp.exception.BusinessException.class, () -> grants.require(committed, "download"));
    }
}
