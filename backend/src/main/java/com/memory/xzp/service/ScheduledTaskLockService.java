package com.memory.xzp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class ScheduledTaskLockService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskLockService.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final String ownerId = UUID.randomUUID().toString();

    @Value("${app.scheduler-lock.enabled:true}")
    private boolean enabled;

    @Value("${app.scheduler-lock.key-prefix:scheduler_lock}")
    private String keyPrefix;

    @Value("${app.scheduler-lock.default-ttl-seconds:300}")
    private long defaultTtlSeconds;

    @Value("${app.scheduler-lock.fallback-open:false}")
    private boolean fallbackOpen;

    public ScheduledTaskLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean runWithLock(String lockName, Duration ttl, Runnable action) {
        return callWithLock(lockName, ttl, () -> {
            action.run();
            return true;
        }, false);
    }

    public <T> T callWithLock(String lockName, Duration ttl, Supplier<T> action, T skippedValue) {
        try (LockLease lease = acquire(lockName, ttl)) {
            if (!lease.acquired()) {
                return skippedValue;
            }
            return action.get();
        }
    }

    public LockLease acquire(String lockName, Duration ttl) {
        String safeName = safeLockName(lockName);
        if (!enabled) {
            return new LockLease(true, null, null);
        }
        String key = keyPrefix + ":" + safeName;
        String token = ownerId + ":" + UUID.randomUUID();
        Duration safeTtl = safeTtl(ttl);
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, safeTtl);
            if (Boolean.TRUE.equals(acquired)) {
                return new LockLease(true, key, token);
            }
            log.debug("Scheduled task lock already held: lockName={}", safeName);
            return new LockLease(false, null, null);
        } catch (RuntimeException e) {
            if (fallbackOpen) {
                log.warn("Scheduled task lock unavailable, running locally: lockName={}, error={}",
                        safeName, e.getMessage());
                return new LockLease(true, null, null);
            }
            log.warn("Scheduled task lock unavailable, skipping task: lockName={}, error={}",
                    safeName, e.getMessage());
            return new LockLease(false, null, null);
        }
    }

    private Duration safeTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return Duration.ofSeconds(Math.max(1L, defaultTtlSeconds));
        }
        return ttl.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : ttl;
    }

    private String safeLockName(String lockName) {
        String value = lockName == null || lockName.isBlank() ? "unnamed" : lockName.trim();
        return value.replaceAll("[^A-Za-z0-9:_-]", "_");
    }

    public final class LockLease implements AutoCloseable {

        private final boolean acquired;
        private final String key;
        private final String token;

        private LockLease(boolean acquired, String key, String token) {
            this.acquired = acquired;
            this.key = key;
            this.token = token;
        }

        public boolean acquired() {
            return acquired;
        }

        @Override
        public void close() {
            if (!acquired || key == null || token == null) {
                return;
            }
            try {
                redisTemplate.execute(RELEASE_SCRIPT, List.of(key), token);
            } catch (RuntimeException e) {
                log.warn("Scheduled task lock release failed: key={}, error={}", key, e.getMessage());
            }
        }
    }
}
