package com.memory.xzp.service;

import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.mapper.UserStorageMapper;
import com.memory.xzp.metrics.BusinessMetrics;
import com.memory.xzp.model.entity.UserStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StorageQuotaService {

    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>("""
            local expired = redis.call('zrangebyscore', KEYS[3], '-inf', ARGV[4], 'LIMIT', 0, 100)
            for _, id in ipairs(expired) do
              local amount = redis.call('hget', KEYS[2], id)
              if amount then
                redis.call('decrby', KEYS[1], amount)
                redis.call('hdel', KEYS[2], id)
              end
              redis.call('zrem', KEYS[3], id)
            end
            if redis.call('hexists', KEYS[2], ARGV[3]) == 1 then
              return redis.call('get', KEYS[1])
            end
            local used = tonumber(ARGV[1])
            local reserved = tonumber(redis.call('get', KEYS[1]) or '0')
            local amount = tonumber(ARGV[2])
            local total = tonumber(ARGV[5])
            if used + reserved + amount > total then
              return -1
            end
            local nextReserved = redis.call('incrby', KEYS[1], amount)
            redis.call('hset', KEYS[2], ARGV[3], amount)
            redis.call('zadd', KEYS[3], tonumber(ARGV[4]) + tonumber(ARGV[6]), ARGV[3])
            return nextReserved
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            local amount = redis.call('hget', KEYS[2], ARGV[1])
            if not amount then
              return 0
            end
            local used = redis.call('decrby', KEYS[1], amount)
            if used < 0 then
              redis.call('set', KEYS[1], 0)
            end
            redis.call('hdel', KEYS[2], ARGV[1])
            redis.call('zrem', KEYS[3], ARGV[1])
            return tonumber(amount)
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final UserStorageMapper userStorageMapper;
    private final BusinessMetrics businessMetrics;

    @Value("${upload.multipart.session-ttl-seconds:7200}")
    private long reservationTtlSeconds;

    public StorageQuotaService(
            StringRedisTemplate redisTemplate,
            UserStorageMapper userStorageMapper,
            BusinessMetrics businessMetrics
    ) {
        this.redisTemplate = redisTemplate;
        this.userStorageMapper = userStorageMapper;
        this.businessMetrics = businessMetrics;
    }

    public void reserve(Long userId, long size, String reservationId) {
        UserStorage storage = userStorageMapper.selectById(userId);
        if (storage == null) {
            businessMetrics.recordQuotaReservation("reserve", "missing_storage", size);
            throw new BusinessException(StatusCode.NOT_FOUND_ERROR, "用户容量信息不存在");
        }
        long used = storage.getUsedSpace() == null ? 0L : storage.getUsedSpace();
        long total = storage.getTotalSpace() == null ? 0L : storage.getTotalSpace();
        long now = System.currentTimeMillis();
        Long result = redisTemplate.execute(
                RESERVE_SCRIPT,
                keys(userId),
                String.valueOf(used),
                String.valueOf(size),
                reservationId,
                String.valueOf(now),
                String.valueOf(total),
                String.valueOf(reservationTtlSeconds * 1000)
        );
        if (result == null || result < 0) {
            businessMetrics.recordQuotaReservation("reserve", "rejected", size);
            throw new BusinessException(StatusCode.PARAMS_ERROR, "存储空间不足");
        }
        businessMetrics.recordQuotaReservation("reserve", "success", size);
    }

    public void release(Long userId, String reservationId) {
        Long releasedBytes = redisTemplate.execute(
                RELEASE_SCRIPT,
                keys(userId),
                reservationId
        );
        businessMetrics.recordQuotaReservation("release", "success", releasedBytes == null ? 0 : releasedBytes);
    }

    public void confirm(Long userId, String reservationId) {
        Long releasedBytes = redisTemplate.execute(
                RELEASE_SCRIPT,
                keys(userId),
                reservationId
        );
        businessMetrics.recordQuotaReservation("confirm", "success", releasedBytes == null ? 0 : releasedBytes);
    }

    private List<String> keys(Long userId) {
        return List.of(usedKey(userId), reservationsKey(userId), expiryKey(userId));
    }

    private String usedKey(Long userId) {
        return "storage:reserved:" + userId;
    }

    private String reservationsKey(Long userId) {
        return "storage:reservations:" + userId;
    }

    private String expiryKey(Long userId) {
        return "storage:reservation-expiry:" + userId;
    }
}
