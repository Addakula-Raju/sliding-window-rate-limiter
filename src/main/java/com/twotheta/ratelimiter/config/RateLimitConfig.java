package com.twotheta.ratelimiter.config;

import com.twotheta.ratelimiter.service.RateLimiter;
import com.twotheta.ratelimiter.service.SlidingWindowRateLimiter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
@ConfigurationProperties(prefix = "ratelimit")
public class RateLimitConfig {

    private int maxRequests = 5;

    private long windowSeconds = 10;

    public int getMaxRequests() {
        return maxRequests;
    }

    public void setMaxRequests(int maxRequests) {
        this.maxRequests = maxRequests;
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(long windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    @Bean
    public RateLimiter rateLimiter() {
        return new SlidingWindowRateLimiter(maxRequests, windowSeconds * 1000);
    }
}
