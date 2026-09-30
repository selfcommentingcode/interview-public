# Rate Limiting — Design Notes

Implements a **process-local, global** request rate limiter using a **leaky
bucket** algorithm, allowing **10 requests per minute** across all clients.

## What was built (the thin version)

Two small classes plus one config property:

| File | Role |
|------|------|
| `ratelimit/LeakyBucketRateLimiter.java` | The algorithm. One shared bucket, thread-safe. |
| `ratelimit/RateLimitFilter.java` | Servlet filter that returns `429` when the bucket overflows. |
| `application.properties` → `app.rate-limit.requests-per-minute=10` | The single tunable knob. |

### How the algorithm works
The bucket holds up to `capacity` units and leaks at a steady rate. On each
request we first "leak" (`level -= elapsedMs * leakRate`, floored at 0), then try
to add one unit. If `level + 1 <= capacity` the request is admitted; otherwise it
is rejected. With `requestsPerMinute = 10` the leak rate is `10/60000` units per
ms and the capacity is `10`, giving a **burst of 10** followed by a **sustained
10/min** (one request freed every 6s).

### Where it runs
A `jakarta.servlet.Filter` at `@Order(0)` — ahead of the existing
`AuthFilter` (`@Order(1)`) — so the limit applies to every request before any
auth work is done. Spring Boot auto-registers `Filter` beans, matching the
pattern already used by `AuthFilter` (no extra registration wiring).

## Design decisions & tradeoffs

- **Global single bucket, not per-client.** The requirement is a global limit, so
  there is no keyed map, no eviction, no memory growth. Simplest possible state:
  two doubles and a timestamp.
- **`synchronized tryAcquire()`.** The critical section is a few arithmetic ops;
  a monitor is more than fast enough and is trivially correct. No lock-free
  cleverness needed at this scale.
- **Filter over interceptor / AOP.** A filter is the thinnest cross-cutting seam
  and lets us short-circuit before auth. It also naturally covers *all* paths.
- **Capacity tied to the rate (both = 10).** One knob to satisfy "10 requests per
  minute." Burst size and sustained rate are intentionally coupled here for
  simplicity (see improvements).
- **Injectable time source.** Production uses `System.currentTimeMillis()`; a
  package-private constructor accepts a `LongSupplier` so unit tests drive time
  deterministically (no `Thread.sleep`). Kept minimal — no full `java.time.Clock`.
- **Applies to `/health` too.** Kept global for simplicity; see improvements for
  why you might exempt it.

## Testing

- **Unit tests** — [`LeakyBucketRateLimiterTest`](src/test/java/com/weavelab/interview/ratelimit/LeakyBucketRateLimiterTest.java)
  covers the algorithm: admit up to capacity then reject, config-driven capacity,
  steady leak of one slot per 6s, full recovery after a minute, and clamping so idle
  time can't bank credit. A controllable `LongSupplier` clock (injected via the
  package-private constructor) makes every assertion deterministic — no `Thread.sleep`.
  [`RateLimitFilterTest`](src/test/java/com/weavelab/interview/ratelimit/RateLimitFilterTest.java)
  covers the filter: admitted requests pass down the chain, rejected ones short-circuit
  with `429` + the JSON body.
- **Coverage** — the rate limiter is at **100% line & branch** coverage (JaCoCo).
  Run `./mvnw test`; the report lands at `target/site/jacoco/index.html`.
- **Postman collection** (`java/postman/`, see [`TESTING.md`](TESTING.md)) — fires a
  12-request burst and asserts the first 10 return `200` and the rest `429` with the
  JSON error body, alongside full CRUD and the auth matrix (valid → `200`,
  missing/bad → `401`). Runs in the Postman app or headless via Newman.

## Alternatives considered

- **Token bucket** — mathematically the mirror image; equally valid. Chose leaky
  bucket per the assignment.
- **Fixed / sliding window counter** — simpler counting, but fixed windows allow
  2× bursts at the boundary; sliding windows cost more memory. Leaky bucket gives
  smooth shaping with O(1) state.
- **Guava `RateLimiter` / Bucket4j / Resilience4j** — production-grade, but pulls
  a dependency for something expressible in ~20 lines; less to show for a
  take-home.
- **Spring Cloud Gateway / API-gateway-level limiting** — right answer at scale,
  out of scope for a single embedded service.

## Areas to improve with more time (robustness & extensibility)

1. **Decouple burst capacity from sustained rate** — expose
   `app.rate-limit.capacity` separately from `requests-per-minute` so bursts can
   be tuned independently.
2. **`429` niceties** — add a `Retry-After` header and rate-limit headers
   (`X-RateLimit-Limit/Remaining/Reset`) so clients can back off intelligently.
3. **Per-client / tiered limits** — key the bucket by `userId` (already available
   from `AuthFilter`) or IP, with a `ConcurrentHashMap` of buckets plus idle
   eviction. Extract a `RateLimiter` interface so global vs per-key strategies are
   swappable.
4. **Exempt or separately limit `/health`** (and other infra paths) so load
   balancer probes are never throttled.
5. **Distributed limiting** — move state to Redis (e.g. a Lua leaky-bucket) when
   running multiple instances; the interface from (3) makes this a drop-in.
6. **Observability** — counter/metric for rejected requests and current bucket
   level (Micrometer), plus a log line on rejection.
7. **Contention** — if the single lock ever becomes hot, switch to an atomic
   CAS loop or a striped design; not warranted at 10/min.
