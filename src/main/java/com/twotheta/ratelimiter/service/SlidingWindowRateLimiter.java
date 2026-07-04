package com.twotheta.ratelimiter.service;

import com.twotheta.ratelimiter.model.ClientRequestLog;
import com.twotheta.ratelimiter.model.RateLimitResult;

import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
public class SlidingWindowRateLimiter implements RateLimiter {

    private final int maxRequests;
    private final long windowMillis;
    private final ConcurrentHashMap<String, ClientRequestLog> clientLogs = new ConcurrentHashMap<>();

    /**
     * @param maxRequests  N — max requests allowed per window
     * @param windowMillis T — window length in milliseconds
     */
    public SlidingWindowRateLimiter(int maxRequests, long windowMillis) {
        if (maxRequests <= 0) {
            throw new IllegalArgumentException("maxRequests must be positive");
        }
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be positive");
        }
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
    }

    @Override
    public RateLimitResult allow(String clientId) {
        long now = System.currentTimeMillis();

        // computeIfAbsent is atomic on ConcurrentHashMap, so two threads racing to
        // create the log for a brand-new client will still end up sharing one instance.
        ClientRequestLog log = clientLogs.computeIfAbsent(clientId, key -> new ClientRequestLog());

        ReentrantLock lock = log.getLock();
        lock.lock();
        try {
            Deque<Long> timestamps = log.getTimestamps();
            long windowStart = now - windowMillis;

            // Evict everything that has aged out of the window.
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= windowStart) {
                timestamps.pollFirst();
            }

            if (timestamps.size() < maxRequests) {
                timestamps.addLast(now);
                int remaining = maxRequests - timestamps.size();
                return new RateLimitResult(true, remaining, 0);
            }

            long oldest = timestamps.peekFirst();
            long retryAfterMillis = (oldest + windowMillis) - now;
            // Round up so we never tell a client to retry a moment too early.
            long retryAfterSeconds = Math.max(1L, (retryAfterMillis + 999) / 1000);
            return new RateLimitResult(false, 0, retryAfterSeconds);
        } finally {
            lock.unlock();
        }
    }
}
