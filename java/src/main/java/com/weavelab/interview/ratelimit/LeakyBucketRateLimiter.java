package com.weavelab.interview.ratelimit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * Process-local, global leaky-bucket rate limiter.
 *
 * <p>A single shared bucket is used for every request (the limit is global, not
 * per-client). The bucket holds up to {@code capacity} units and leaks at a
 * steady rate. Each admitted request adds one unit; if adding a unit would
 * overflow the bucket the request is rejected. This yields a sustained rate of
 * {@code requestsPerMinute} with a burst allowance equal to the capacity.
 */
@Component
public class LeakyBucketRateLimiter {

    private final double capacity;
    private final double leakPerMs;
    private final LongSupplier clock;

    private double level;
    private long lastLeakAt;

    @Autowired
    public LeakyBucketRateLimiter(
            @Value("${app.rate-limit.requests-per-minute:10}") int requestsPerMinute) {
        this(requestsPerMinute, System::currentTimeMillis);
    }

    /**
     * Constructor with an explicit time source. Package-private so unit tests can
     * inject a controllable clock and assert leak/recovery deterministically,
     * without {@code Thread.sleep}.
     */
    LeakyBucketRateLimiter(int requestsPerMinute, LongSupplier clock) {
        this.capacity = requestsPerMinute;
        this.leakPerMs = requestsPerMinute / 60_000.0;
        this.clock = clock;
        this.lastLeakAt = clock.getAsLong();
    }

    /**
     * Attempts to admit a single request.
     *
     * @return {@code true} if the request is within the limit, {@code false} if it should be rejected
     */
    public synchronized boolean tryAcquire() {
        long now = clock.getAsLong();
        level = Math.max(0, level - (now - lastLeakAt) * leakPerMs);
        lastLeakAt = now;

        if (level + 1 <= capacity) {
            level += 1;
            return true;
        }
        return false;
    }
}
