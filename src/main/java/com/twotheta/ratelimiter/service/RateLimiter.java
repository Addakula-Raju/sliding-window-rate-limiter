package com.twotheta.ratelimiter.service;

import com.twotheta.ratelimiter.model.RateLimitResult;

public interface RateLimiter {


    RateLimitResult allow(String clientId);
}
