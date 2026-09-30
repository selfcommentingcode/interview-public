package com.weavelab.interview.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LeakyBucketRateLimiter}. A controllable clock makes every
 * assertion deterministic — no {@code Thread.sleep}, no timing flakiness.
 */
class LeakyBucketRateLimiterTest {

    /** A time source the test drives explicitly. */
    private static final class FakeClock implements LongSupplier {
        private long millis;
        FakeClock(long startMillis) { this.millis = startMillis; }
        void advance(long ms) { this.millis += ms; }
        @Override public long getAsLong() { return millis; }
    }

    @Test
    void admitsUpToCapacityThenRejects() {
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(10, new FakeClock(0));

        for (int i = 1; i <= 10; i++) {
            assertTrue(limiter.tryAcquire(), "request " + i + " should be within the limit");
        }
        assertFalse(limiter.tryAcquire(), "11th request should be rejected");
        assertFalse(limiter.tryAcquire(), "12th request should be rejected");
    }

    @Test
    void capacityIsConfigDriven() {
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(3, new FakeClock(0));

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire(), "4th request should exceed a capacity of 3");
    }

    @Test
    void leaksOneSlotAtTheSteadyRate() {
        FakeClock clock = new FakeClock(0);
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(10, clock);

        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire());
        }
        assertFalse(limiter.tryAcquire(), "bucket is full");

        // At 10/min the bucket leaks exactly one unit every 6 seconds.
        clock.advance(6_000);
        assertTrue(limiter.tryAcquire(), "one slot should have freed after 6s");
        assertFalse(limiter.tryAcquire(), "only a single slot should have freed");
    }

    @Test
    void fullyRecoversAfterOneMinute() {
        FakeClock clock = new FakeClock(0);
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(10, clock);

        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire());
        }
        assertFalse(limiter.tryAcquire());

        clock.advance(60_000); // a full minute -> bucket fully drains
        for (int i = 1; i <= 10; i++) {
            assertTrue(limiter.tryAcquire(), "request " + i + " after a full drain");
        }
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void idleTimeDoesNotBankCreditBeyondCapacity() {
        FakeClock clock = new FakeClock(0);
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(10, clock);

        // Sit idle far longer than a minute, then burst: the level is clamped at 0,
        // so no extra credit accrues — at most `capacity` requests are admitted.
        clock.advance(10 * 60_000);
        for (int i = 1; i <= 10; i++) {
            assertTrue(limiter.tryAcquire(), "request " + i + " on an idle bucket");
        }
        assertFalse(limiter.tryAcquire(), "idle time must not bank credit beyond capacity");
    }
}
