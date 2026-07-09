package com.memory.xzp.mapper;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTaskMapperSqlTest {

    @Test
    void parsesBatchRetryAndCancelStatements() {
        Configuration configuration = new Configuration();
        configuration.addMapper(AsyncTaskMapper.class);
        Map<String, Object> parameters = Map.of("taskIds", List.of(42L, 43L));

        BoundSql retrySql = boundSql(
                configuration,
                "com.memory.xzp.mapper.AsyncTaskMapper.resetForAdminRetry",
                parameters
        );
        BoundSql selectRetryableSql = boundSql(
                configuration,
                "com.memory.xzp.mapper.AsyncTaskMapper.selectRetryableTaskIds",
                parameters
        );
        BoundSql cancelSql = boundSql(
                configuration,
                "com.memory.xzp.mapper.AsyncTaskMapper.cancelDeadTasks",
                parameters
        );

        assertTrue(retrySql.getSql().contains("status IN ('FAILED', 'DEAD')"));
        assertTrue(retrySql.getSql().contains("id IN"));
        assertTrue(selectRetryableSql.getSql().contains("status IN ('FAILED', 'DEAD')"));
        assertTrue(selectRetryableSql.getSql().contains("ORDER BY id"));
        assertTrue(cancelSql.getSql().contains("status = 'CANCELLED'"));
        assertTrue(cancelSql.getSql().contains("id IN"));
    }

    private BoundSql boundSql(
            Configuration configuration,
            String statementId,
            Map<String, Object> parameters
    ) {
        MappedStatement statement = configuration.getMappedStatement(statementId);
        return statement.getBoundSql(parameters);
    }
}
