package com.memory.xzp.config.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    double permitsPerSecond();

    long timeoutMillis() default 0L;

    Scope scope() default Scope.USER;

    enum Scope {
        GLOBAL,
        USER,
        IP
    }
}
