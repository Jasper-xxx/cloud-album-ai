package com.memory.xzp.config.ratelimit;

import cn.dev33.satoken.stp.StpUtil;
import com.google.common.util.concurrent.RateLimiter;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.utils.auth.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Aspect
@Component
public class RateLimitAspect {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);
    private static final int MAX_LIMITERS = 20_000;
    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>("""
            local bucket = redis.call('hmget', KEYS[1], 'tokens', 'timestamp')
            local tokens = tonumber(bucket[1])
            local timestamp = tonumber(bucket[2])
            local now = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local capacity = tonumber(ARGV[3])
            local ttl = tonumber(ARGV[4])
            local requested = tonumber(ARGV[5])
            if tokens == nil then
              tokens = capacity
            end
            if timestamp == nil then
              timestamp = now
            end
            local elapsed = math.max(0, now - timestamp) / 1000
            tokens = math.min(capacity, tokens + elapsed * rate)
            local allowed = 0
            if tokens >= requested then
              tokens = tokens - requested
              allowed = 1
            end
            redis.call('hmset', KEYS[1], 'tokens', tostring(tokens), 'timestamp', tostring(now))
            redis.call('expire', KEYS[1], ttl)
            return allowed
            """, Long.class);

    private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final IpUtil ipUtil;

    @Value("${app.rate-limit.distributed-enabled:true}")
    private boolean distributedEnabled;

    @Value("${app.rate-limit.redis-key-prefix:rate_limit}")
    private String redisKeyPrefix;

    @Value("${app.rate-limit.burst-seconds:1}")
    private long burstSeconds;

    @Value("${app.rate-limit.fallback-open:true}")
    private boolean fallbackOpen;

    public RateLimitAspect(StringRedisTemplate redisTemplate, IpUtil ipUtil) {
        this.redisTemplate = redisTemplate;
        this.ipUtil = ipUtil;
    }

    @Around("@annotation(rateLimit)")
    public Object limit(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        String key = method.getDeclaringClass().getName() + "#" + method.getName()
                + ":" + resolveScopeKey(rateLimit.scope());

        if (limiters.size() > MAX_LIMITERS) {
            limiters.clear();
        }

        RateLimiter limiter = limiters.computeIfAbsent(
                key,
                ignored -> RateLimiter.create(rateLimit.permitsPerSecond())
        );
        if (Double.compare(limiter.getRate(), rateLimit.permitsPerSecond()) != 0) {
            limiter.setRate(rateLimit.permitsPerSecond());
        }
        boolean acquired = rateLimit.timeoutMillis() > 0
                ? limiter.tryAcquire(rateLimit.timeoutMillis(), TimeUnit.MILLISECONDS)
                : limiter.tryAcquire();
        if (!acquired || !acquireDistributed(key, rateLimit)) {
            throw new BusinessException(StatusCode.RATE_LIMIT_ERROR, "请求过于频繁，请稍后重试");
        }
        return joinPoint.proceed();
    }

    private boolean acquireDistributed(String key, RateLimit rateLimit) {
        if (!distributedEnabled) {
            return true;
        }
        if (rateLimit.timeoutMillis() <= 0) {
            return tryAcquireDistributedOnce(key, rateLimit);
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(rateLimit.timeoutMillis());
        do {
            if (tryAcquireDistributedOnce(key, rateLimit)) {
                return true;
            }
            sleepQuietly(Math.min(50L, Math.max(1L, rateLimit.timeoutMillis())));
        } while (System.nanoTime() < deadline);
        return false;
    }

    private boolean tryAcquireDistributedOnce(String key, RateLimit rateLimit) {
        try {
            long capacity = Math.max(1L, (long) Math.ceil(rateLimit.permitsPerSecond() * Math.max(1L, burstSeconds)));
            long ttlSeconds = Math.max(60L, Math.max(1L, burstSeconds) * 2L + 60L);
            Long allowed = redisTemplate.execute(
                    TOKEN_BUCKET_SCRIPT,
                    List.of(redisKeyPrefix + ":" + key),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(rateLimit.permitsPerSecond()),
                    String.valueOf(capacity),
                    String.valueOf(ttlSeconds),
                    "1"
            );
            return Long.valueOf(1L).equals(allowed);
        } catch (RuntimeException e) {
            if (!fallbackOpen) {
                log.warn("Distributed rate limiter unavailable, rejecting request: key={}, error={}",
                        key, e.getMessage());
                return false;
            }
            log.warn("Distributed rate limiter unavailable, falling back to local limiter: key={}, error={}",
                    key, e.getMessage());
            return true;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String resolveScopeKey(RateLimit.Scope scope) {
        if (scope == RateLimit.Scope.GLOBAL) {
            return "global";
        }
        if (scope == RateLimit.Scope.USER && StpUtil.isLogin()) {
            return "user:" + StpUtil.getLoginIdAsString();
        }
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes == null ? null : attributes.getRequest();
        return "ip:" + clientIp(request);
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        return ipUtil.getClientIpAddress(request);
    }
}
