package com.twotheta.ratelimiter.interceptor;

import com.twotheta.ratelimiter.model.RateLimitResult;
import com.twotheta.ratelimiter.service.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;


@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    static final String CLIENT_ID_HEADER = "X-Client-Id";
    static final String ANONYMOUS_CLIENT_ID = "anonymous";
    static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    static final String RETRY_AFTER_HEADER = "Retry-After";

    private final RateLimiter rateLimiter;

    public RateLimitInterceptor(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clientId = request.getHeader(CLIENT_ID_HEADER);

        // Edge case: no X-Client-Id header supplied. Rather than fail the request,
        // we bucket all such callers under a single "anonymous" client so the API
        // still works, though callers sharing this bucket share one quota.
        if (clientId == null || clientId.isBlank()) {
            clientId = ANONYMOUS_CLIENT_ID;
        }

        RateLimitResult result = rateLimiter.allow(clientId);

        response.setHeader(REMAINING_HEADER,
                String.valueOf(result.getRemainingRequests()));

        if (!result.isAllowed()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(RETRY_AFTER_HEADER,
                    String.valueOf(result.getRetryAfterSeconds()));
            return false;
        }

        return true;
    }
}