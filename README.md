# Configurable Per-Client Rate Limiter

A production-style Spring Boot service implementing a configurable, per-client, thread-safe
rate limiter using the **Sliding Window Log** algorithm.

Built for the Two Theta take-home assignment (Software Engineering Intern — Build a Rate Limiter).

---

## Project Overview

The core problem: for every incoming request, answer one question fast — *"has this client
already used up its quota, or can this request go through?"*

This project implements that decision function (`RateLimiter.allow(clientId)`) as a standalone,
framework-independent service, then exposes it over HTTP via a `GET /api/data` endpoint protected
by a Spring `HandlerInterceptor`.

```
src/main/java/com/twotheta/ratelimiter
│
├── RateLimiterApplication.java        Spring Boot entry point
│
├── controller/
│   └── DemoController.java            GET /api/data — the protected resource
│
├── service/
│   ├── RateLimiter.java               Decision-function interface
│   └── SlidingWindowRateLimiter.java  Sliding Window Log implementation
│
├── model/
│   ├── RateLimitResult.java           allowed / remainingRequests / retryAfterSeconds
│   └── ClientRequestLog.java          Per-client timestamp deque + lock
│
├── config/
│   ├── RateLimitConfig.java           Binds ratelimit.* properties, builds the RateLimiter bean
│   └── WebConfig.java                 Registers the interceptor on /api/**
│
└── interceptor/
    └── RateLimitInterceptor.java      Reads X-Client-Id, sets response headers, returns 429
```

---

## Algorithm Choice: Sliding Window Log

### Why this algorithm

Two Theta's brief explicitly asked for accurate, rolling-window enforcement rather than the
simpler (but bursty) fixed-window approach. Sliding Window Log was chosen because:

- **Accuracy** — it looks at the *actual* timestamps of a client's last requests, so the limit
  is enforced on a true rolling basis. There's no "reset boundary" a client can exploit.
- **No boundary burst problem** — a fixed window resets at, say, every :00 and :10 second mark.
  A client can fire N requests at 0:09.9 and another N at 0:10.1 — 2N requests in ~200ms, even
  though the configured limit is "N per 10s". Sliding Window Log makes that impossible because
  the window always trails the *current* moment, not a clock boundary.
- **Simplicity of the decision function** — the check is "how many timestamps are in
  `(now - T, now]`?" — easy to reason about and to test.

### Advantages

- Exact enforcement — never allows more than N requests in *any* rolling T-second interval.
- Naturally gives an exact `retryAfterSeconds`: it's just "when does the oldest timestamp expire".
- No synchronization dance between a "count" and a "window start" field — a single ordered log
  is the whole state.

### Trade-offs

- **Memory** — each client stores up to N timestamps (a `long` each) instead of a single counter.
  For this assignment's scale (N=5) this is trivial; at very high N or very many clients, a
  counter-based approach (Sliding Window Counter) would use less memory at the cost of being an
  *approximation* rather than an exact enforcement.
- **Eviction cost** — every call does a linear scan from the front of the deque to evict expired
  entries. In the worst case this is O(N) per call, but since N is the configured limit (typically
  small, e.g. 5–100) this is effectively O(1) in practice.
- **No cross-instance state** — like all the algorithms considered, this in-memory implementation
  only rate-limits correctly within a single JVM instance. See "Future Improvements" for the
  distributed case.

### Memory considerations

Per-client memory is `O(N)` (N = max requests per window), not `O(1)` like a token bucket or
fixed-window counter. For a service configured at "5 requests / 10 seconds" this is negligible
(a handful of 8-byte longs per client). Idle clients keep their (now-empty) log entry in the map
indefinitely in this implementation — see "Future Improvements" for eviction of stale client
entries.

---

## Concurrency Handling

Two things must be true under concurrent load:

1. Two different clients must never block each other.
2. Two simultaneous requests **for the same client** must never both be admitted when only one
   slot remains (a classic check-then-act race).

**Design:**

- Per-client state (`ClientRequestLog`: a timestamp deque + a `ReentrantLock`) is stored in a
  `ConcurrentHashMap<String, ClientRequestLog>`, keyed by `clientId`.
  `computeIfAbsent` is used to create a client's log, which is atomic even if two threads race to
  create the same new client's entry simultaneously.
- The **entire** evict → check → record sequence for a given client is wrapped in that client's
  own `ReentrantLock`. This means:
  - Requests for *different* clients never contend for the same lock — full parallelism across
    clients.
  - Requests for the *same* client are serialized just long enough to make the
    read-then-write decision atomic, closing the race window.

This was verified with a dedicated test: 50 threads fire simultaneously at the same client with a
limit of 5, using a CountDownLatch to release them at the same instant. The test asserts that no
more than 5 requests are admitted concurrently for the same client. In practice, the test
consistently admits exactly 5 requests.

---

## Edge Cases

1. **Exactly at the limit** — the Nth request is allowed (remaining = 0); the (N+1)th is blocked.
   Covered by `exactlyAtLimitBoundaryIsAllowedButNextIsBlocked`.
2. **Window boundary behavior** — because this is a true sliding log (not a fixed window), a
   request is evaluated against the actual timestamps in the trailing T-second interval, so there
   is no clock-aligned boundary a client can exploit to double their effective rate.
3. **New client with no history** — `computeIfAbsent` transparently creates an empty log and the
   first request is allowed with `remaining = N - 1`. Covered by `newClientWithNoHistoryIsAllowed`.
4. **Simultaneous requests** — handled via the per-client lock described above; covered by the
   concurrency test.
5. **Missing `X-Client-Id` header** — rather than reject the request outright, it is bucketed
   under a shared `"anonymous"` client id, so the API still degrades gracefully (all headerless
   callers share one quota). This is a deliberate assumption — see below.

## Assumptions

- Client identity is supplied by the caller via the `X-Client-Id` header (in a real system this
  would more likely be an authenticated user id or API key, but the header approach matches the
  assignment's spec and keeps the demo self-contained).
- Requests without an `X-Client-Id` header are not rejected; they share a single `"anonymous"`
  bucket rather than bypassing the limiter entirely or getting a 400.
- Limits are per-instance/in-memory: acceptable for a single-instance demo; see "Future
  Improvements" for the distributed case.
- `retryAfterSeconds` is rounded **up** to whole seconds (never 0) so a client that respects the
  header will never retry a moment too early.

---

## API Usage

### Endpoint

```
GET /api/data
Header: X-Client-Id: <string>
```

### Responses

**Allowed:**
```
HTTP/1.1 200 OK
X-RateLimit-Remaining: 3

{"message":"Here is your data.","timestamp":"2026-07-04T10:15:30.123Z"}
```

**Blocked:**
```
HTTP/1.1 429 Too Many Requests
Retry-After: 7
X-RateLimit-Remaining: 0
```

### Sample requests (curl)

```bash
# Successful request
curl -i -H "X-Client-Id: alice" http://localhost:8080/api/data

# Fire 8 requests quickly to see the 6th–8th get blocked
for i in $(seq 1 8); do
  curl -s -o /dev/null -w "Request $i -> %{http_code}\n" \
    -H "X-Client-Id: alice" http://localhost:8080/api/data
done
```

---

## Sample Scenario (from the assignment)

**Config:** 5 requests / 10 seconds. **Client:** `alice`. **8 requests fired within the first 3 seconds.**

With the Sliding Window Log:

| Request # | Time (s) | Timestamps in window before | Decision | Remaining |
|-----------|----------|------------------------------|----------|-----------|
| 1         | 0.0      | []                           | Allowed  | 4         |
| 2         | 0.4      | [0.0]                        | Allowed  | 3         |
| 3         | 0.8      | [0.0, 0.4]                   | Allowed  | 2         |
| 4         | 1.2      | [0.0, 0.4, 0.8]              | Allowed  | 1         |
| 5         | 1.6      | [0.0, 0.4, 0.8, 1.2]         | Allowed  | 0         |
| 6         | 2.0      | [0.0, 0.4, 0.8, 1.2, 1.6]    | **Blocked**, retryAfter ≈ 8s | 0 |
| 7         | 2.4      | (same 5 still in window)     | **Blocked** | 0 |
| 8         | 2.8      | (same 5 still in window)     | **Blocked** | 0 |

**First 5 allowed, next 3 blocked** — matches the assignment's expected outcome.

After waiting the full 10 seconds (past t=10.0, when the request at t=0.0 has aged out — in fact
all 5 original timestamps have aged out by t=10.0), alice sends 2 more requests:

| Request # | Time (s) | Timestamps in window before | Decision |
|-----------|----------|------------------------------|----------|
| 9         | ≥10.0    | []                           | Allowed  |
| 10        | ≥10.0    | [t9]                         | Allowed  |

**Both allowed**, as expected — the window has fully rolled over.

---

## Running the Project

```bash
# Run tests
mvn clean test

# Run the application
mvn spring-boot:run
```

The server starts on `http://localhost:8080`.

---

## Test Coverage

| # | Test | File |
|---|------|------|
| 1 | Requests under the limit are allowed | `SlidingWindowRateLimiterTest.allowsRequestsUnderTheLimit` |
| 2 | Requests above the limit are blocked | `SlidingWindowRateLimiterTest.blocksRequestsOverTheLimit` |
| 3 | Window expiration allows requests again | `SlidingWindowRateLimiterTest.windowExpirationAllowsRequestsAgain` |
| 4 | Per-client isolation | `SlidingWindowRateLimiterTest.perClientIsolation` |
| 5 | Concurrency — 50 simultaneous requests never exceed the limit | `SlidingWindowRateLimiterTest.concurrentRequestsForSameClientNeverExceedLimit` |
| — | Edge case: new client with no history | `SlidingWindowRateLimiterTest.newClientWithNoHistoryIsAllowed` |
| — | Edge case: exactly at the limit boundary | `SlidingWindowRateLimiterTest.exactlyAtLimitBoundaryIsAllowedButNextIsBlocked` |
| — | Full HTTP contract (200/429 + headers) | `DemoControllerIntegrationTest.allowsRequestsUnderLimitThenReturns429WithHeaders` |
| — | HTTP-level per-client isolation | `DemoControllerIntegrationTest.differentClientIdsAreIsolated` |
| — | Missing header falls back to anonymous bucket | `DemoControllerIntegrationTest.missingClientIdHeaderStillWorksViaAnonymousBucket` |

---

## Future Improvements

- **Redis-based distributed rate limiting** — move the per-client log into Redis (e.g. a sorted
  set keyed by clientId with timestamps as scores, `ZREMRANGEBYSCORE` to evict, `ZCARD` to count)
  so the limiter is correct across multiple service instances behind a load balancer.
- **Token Bucket implementation** — as an alternative algorithm offering `O(1)` memory per client
  and support for controlled burst allowances, useful if strict rolling-window accuracy is less
  important than raw throughput/memory efficiency.
- **Dynamic configuration** — allow per-client or per-tier limits (e.g. free vs. paid API keys)
  and hot-reloading of limits without a restart, rather than one global limit from
  `application.properties`.
- **Metrics and monitoring** — expose counters (allowed/blocked per client, current utilization)
  via Micrometer/Actuator so operators can see rate-limiting behavior in production dashboards
  and alert on clients that are frequently throttled.
- **Stale client eviction** — currently a client's log entry stays in the map forever, even if it
  becomes empty after eviction. A scheduled sweep (or a TTL-based cache like Caffeine) would
  reclaim memory for clients that stop sending requests.
