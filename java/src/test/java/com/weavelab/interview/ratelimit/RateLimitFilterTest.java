package com.weavelab.interview.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RateLimitFilter} — verifies it passes admitted requests
 * down the chain and short-circuits rejected ones with a 429 JSON response.
 */
class RateLimitFilterTest {

    @Test
    void passesRequestDownTheChainWhenWithinLimit() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new LeakyBucketRateLimiter(10, () -> 0L));

        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        verify(res, never()).setStatus(anyInt());
    }

    @Test
    void rejectsWith429JsonWhenOverLimit() throws Exception {
        // Capacity of 1: the first request is admitted, the second overflows.
        RateLimitFilter filter = new RateLimitFilter(new LeakyBucketRateLimiter(1, () -> 0L));
        HttpServletRequest req = mock(HttpServletRequest.class);

        // First request — admitted, passes through.
        FilterChain admitted = mock(FilterChain.class);
        filter.doFilter(req, mock(HttpServletResponse.class), admitted);
        verify(admitted, times(1)).doFilter(any(), any());

        // Second request — rejected with 429 and a JSON body; chain is NOT invoked.
        FilterChain blocked = mock(FilterChain.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(res.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilter(req, res, blocked);

        verify(res).setStatus(429);
        verify(res).setContentType("application/json");
        verify(blocked, never()).doFilter(any(), any());
        assertTrue(body.toString().contains("rate limit exceeded"),
                "429 response should carry the JSON error body");
    }
}
