package com.twotheta.ratelimiter.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantLock;

public class ClientRequestLog {

    private final Deque<Long> timestamps = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();

    public Deque<Long> getTimestamps() {
        return timestamps;
    }

    public ReentrantLock getLock() {
        return lock;
    }
}
