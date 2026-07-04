package com.twotheta.ratelimiter.service;

import com.twotheta.ratelimiter.model.RateLimitResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlidingWindowRateLimiterTest {

    // --- Test 1: requests under the limit are allowed -----------------------------

    @Test
    void allowsRequestsUnderTheLimit() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(5, 10_000);

        for (int i = 1; i <= 5; i++) {
            RateLimitResult result = limiter.allow("alice");
            assertTrue(result.isAllowed(), "request " + i + " should be allowed");
            assertEquals(5 - i, result.getRemainingRequests());
        }
    }

    // --- Test 2: requests above the limit are blocked ------------------------------

    @Test
    void blocksRequestsOverTheLimit() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(5, 10_000);

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.allow("alice").isAllowed());
        }

        RateLimitResult sixth = limiter.allow("alice");
        assertFalse(sixth.isAllowed());
        assertEquals(0, sixth.getRemainingRequests());
        assertTrue(sixth.getRetryAfterSeconds() > 0, "blocked response should include a retry hint");
    }

    // --- Test 3: window expiration allows requests again ---------------------------

    @Test
    void windowExpirationAllowsRequestsAgain() throws InterruptedException {
        // Small window so the test runs fast: 2 requests per 300ms.
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(2, 300);

        assertTrue(limiter.allow("bob").isAllowed());
        assertTrue(limiter.allow("bob").isAllowed());
        assertFalse(limiter.allow("bob").isAllowed(), "3rd request within the window should be blocked");

        Thread.sleep(350); // let the window fully roll over

        RateLimitResult afterWait = limiter.allow("bob");
        assertTrue(afterWait.isAllowed(), "request after window expiry should be allowed again");
    }

    // --- Test 4: per-client isolation ------------------------------------------------

    @Test
    void perClientIsolation() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(2, 10_000);

        assertTrue(limiter.allow("alice").isAllowed());
        assertTrue(limiter.allow("alice").isAllowed());
        assertFalse(limiter.allow("alice").isAllowed(), "alice should be exhausted");

        // bob has made zero requests and must be unaffected by alice's usage.
        RateLimitResult bobFirst = limiter.allow("bob");
        assertTrue(bobFirst.isAllowed());
        assertEquals(1, bobFirst.getRemainingRequests());
    }

    // --- Test 5: concurrency - simultaneous requests never exceed the limit --------

    @Test
    void concurrentRequestsForSameClientNeverExceedLimit() throws InterruptedException {
        int maxRequests = 5;
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(maxRequests, 10_000);

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finishLine = new CountDownLatch(threadCount);
        AtomicInteger allowedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLine.await();
                    if (limiter.allow("concurrent-client").isAllowed()) {
                        allowedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLine.countDown();
                }
            });
        }

        startLine.countDown(); // release all 50 threads at once
        boolean completed = finishLine.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "all threads should finish within the timeout");
        assertEquals(maxRequests, allowedCount.get(),
                "exactly " + maxRequests + " of " + threadCount + " concurrent requests should be allowed");
    }

    // --- Edge case: brand-new client with no history --------------------------------

    @Test
    void newClientWithNoHistoryIsAllowed() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(3, 10_000);

        RateLimitResult result = limiter.allow("brand-new-client");

        assertTrue(result.isAllowed());
        assertEquals(2, result.getRemainingRequests());
    }

    // --- Edge case: exactly at the limit boundary ------------------------------------

    @Test
    void exactlyAtLimitBoundaryIsAllowedButNextIsBlocked() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1, 10_000);

        RateLimitResult first = limiter.allow("carol");
        assertTrue(first.isAllowed());
        assertEquals(0, first.getRemainingRequests());

        RateLimitResult second = limiter.allow("carol");
        assertFalse(second.isAllowed());
    }
}
