package com.weavelab.interview.ratelimit;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Rejects requests that exceed the global rate limit with HTTP 429.
 *
 * <p>Runs before {@link com.weavelab.interview.auth.AuthFilter} so that the limit
 * is applied to every incoming request, regardless of authentication.
 */
@Component
@Order(0)
public class RateLimitFilter implements Filter {

    private final LeakyBucketRateLimiter rateLimiter;

    public RateLimitFilter(LeakyBucketRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!rateLimiter.tryAcquire()) {
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"rate limit exceeded\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}
