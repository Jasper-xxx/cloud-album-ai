package com.memory.xzp.config;

import org.flywaydb.core.api.MigrationState;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
public class FlywayMigrationStrategyConfig {

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            boolean hasFailedMigration = Arrays.stream(flyway.info().all())
                    .anyMatch(info -> info.getState() == MigrationState.FAILED);
            if (hasFailedMigration) {
                flyway.repair();
            }
            flyway.migrate();
        };
    }
}
