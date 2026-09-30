# Request Lifecycle & Flows (Java implementation)

End-to-end view of how a request travels through the Spring Boot server, how the
rate limiter fits in, and what the concurrency / background story is.

> Rendered PNGs of every diagram below live in [`java/diagrams/`](diagrams/).
> The fenced ` ```mermaid ` blocks also render directly on GitHub, in IntelliJ,
> and at <https://mermaid.live>.

## TL;DR — sync vs async

- **Per-request processing is fully synchronous / blocking.** One Tomcat worker
  thread carries a request from the first filter all the way to the JDBC/file I/O
  and back. There is no `@Async`, no `CompletableFuture`, no message queue, and no
  reactive/WebFlux — so "asynchronous" here does **not** mean offloaded request
  work.
- What *is* concurrent / background:
  - **Concurrency** — many requests run in parallel, each on its own worker
    thread, contending on the single `synchronized` rate-limiter bucket and on
    SQLite's single writer lock (Diagram 5).
  - **Background lifecycle** — `CommandLineRunner`s (`StartupCheck`, `SeedCommand`)
    run once at boot, independent of any request (Diagram 4).

---

## 1. System architecture (components & wiring)

How the pieces tie together and the direction data flows on the way in.

```mermaid
flowchart LR
  %% file: 01-architecture
  Client([HTTP Client])

  subgraph Tomcat["Embedded Tomcat — thread per request"]
    direction TB
    subgraph FilterChain["Servlet Filter chain (ordered)"]
      RLF["RateLimitFilter<br/>@Order(0)"]
      AF["AuthFilter<br/>@Order(1)"]
    end
    DS["DispatcherServlet"]
    AI["AuthInterceptor<br/>guards /api/**"]
    subgraph Controllers["Controllers"]
      HC[HealthController]
      CC[ContactController]
      FC[FileController]
      RC[ReportController]
      AC[AuthController]
    end
  end

  RL["LeakyBucketRateLimiter<br/>singleton, shared bucket"]

  subgraph Repos["Repositories — JdbcTemplate"]
    CR[ContactRepository]
    FR[FileRepository]
    AR[ActivityRepository]
  end

  DB[("SQLite<br/>data/app.db")]
  FS[["Filesystem<br/>data/files/"]]

  Client -->|"request"| RLF
  RLF -->|"tryAcquire()"| RL
  RLF --> AF --> DS --> AI --> Controllers

  CC --> CR
  FC --> FR
  FC --> FS
  CC -->|"log()"| AR
  FC -->|"log()"| AR
  RC -->|"log()"| AR

  CR --> DB
  AR --> DB
  FR --> DB
```

**Notes**
- The rate limiter is a single shared bean — the limit is *global*, not
  per-client. It is consulted in the very first filter, before auth.
- `/health` and `/api/auth/token` go through the same chain; only `/api/**` is
  gated by `AuthInterceptor`.

---

## 2. Synchronous request lifecycle (happy path + short-circuits)

The full life of a request on a single thread, including the two places it can be
cut short: **429** (rate limited) and **401** (unauthenticated `/api/**`).

```mermaid
sequenceDiagram
  autonumber
  %% file: 02-sync-request-lifecycle
  actor C as Client
  participant T as Tomcat worker thread
  participant RLF as RateLimitFilter
  participant RL as LeakyBucketRateLimiter
  participant AF as AuthFilter
  participant DS as DispatcherServlet
  participant AI as AuthInterceptor
  participant Ctrl as Controller
  participant Repo as Repository (JDBC)
  participant DB as SQLite / Filesystem

  C->>T: HTTP request
  T->>RLF: doFilter()
  RLF->>RL: tryAcquire() [synchronized]

  alt bucket overflow
    RL-->>RLF: false
    RLF-->>C: 429 (rate limit exceeded)
  else within limit
    RL-->>RLF: true
    RLF->>AF: chain.doFilter()
    AF->>AF: read Bearer token, validate email regex
    Note over AF: sets request attr userId if the token is a valid email
    AF->>DS: chain.doFilter()
    DS->>AI: preHandle()

    alt path is /api/** and userId missing
      AI-->>C: 401 (Unauthorized)
    else authorized (or /health)
      AI-->>DS: true
      DS->>Ctrl: invoke handler method
      Ctrl->>Repo: query / update
      Repo->>DB: SQL or file I/O (blocking)
      DB-->>Repo: rows / bytes
      Repo-->>Ctrl: result

      opt mutating action (create/update/delete/import/export/upload/...)
        Ctrl->>Repo: activityRepository.log(...)
        Repo->>DB: INSERT activity_log
      end

      Ctrl-->>DS: ResponseEntity
      DS-->>C: 2xx JSON (Jackson, snake_case)
    end
  end
```

**Key ordering fact:** rate limiting happens *before* authentication, so an
unauthenticated flood still counts against — and is stopped by — the global
limit.

---

## 3. Leaky-bucket decision (the algorithm)

What `LeakyBucketRateLimiter.tryAcquire()` does on each call. With
`app.rate-limit.requests-per-minute = 10`: `capacity = 10` and
`leakPerMs = 10 / 60000`, i.e. one unit drains every 6 seconds.

```mermaid
flowchart TD
  %% file: 03-leaky-bucket-decision
  A["Request enters RateLimitFilter"] --> B["now = System.currentTimeMillis()"]
  B --> C["drain: level = max(0, level - (now - lastLeakAt) * leakPerMs)<br/>lastLeakAt = now"]
  C --> D{"level + 1 <= capacity ?"}
  D -->|yes| E["level += 1<br/>return true"]
  E --> F["chain.doFilter() → continue down the chain"]
  D -->|no| G["return false"]
  G --> H["HTTP 429 + JSON error body"]
```

Behaviour: allows a **burst of up to 10**, then throttles to a **sustained 10 per
minute**; the bucket recovers continuously as time passes.

---

## 4. Startup & background lifecycle (runs once at boot)

These flows are independent of any HTTP request — the closest thing to
"asynchronous" work in the app.

```mermaid
sequenceDiagram
  autonumber
  %% file: 04-startup-background
  participant M as main
  participant Spring as Spring context
  participant DSC as DataSourceConfig
  participant DB as SQLite
  participant Tomcat as Tomcat
  participant Seed as SeedCommand
  participant SC as StartupCheck

  M->>Spring: SpringApplication.run()
  Spring->>DSC: build jdbcTemplate bean
  DSC->>DB: mkdir data/ and data/files/, then run schema.sql
  Spring->>Spring: register RateLimitFilter(0), AuthFilter(1), AuthInterceptor
  Spring->>Tomcat: start on :8080
  Note over Spring,SC: context ready - CommandLineRunners fire by @Order

  opt app.seed=true (SeedCommand, order 0 - runs first)
    Spring->>Seed: run(args)
    Seed->>DB: batch-insert contacts / files / activity (if empty)
    Seed-->>M: System.exit(0)
  end

  Spring->>SC: run(args) - StartupCheck, order 1
  alt DB empty and startup-check enabled
    SC-->>M: print the seed hint, then System.exit(1)
  else DB has data
    SC-->>Spring: continue
  end
```

> **Runner ordering (fixed in this work):** `SeedCommand` is `@Order(0)`, so it runs
> *before* `StartupCheck` (`@Order(1)`). With `--app.seed=true` (i.e. `make seed`) the
> seeder populates a fresh DB and exits `0` before `StartupCheck` runs; `StartupCheck`
> only trips (prints a hint, then `System.exit(1)`) when you start *without* seeding
> against an empty DB. Previously the reverse order let `StartupCheck` exit before the
> seeder ever ran, so `make seed` silently failed on a fresh database.

---

## 5. Concurrency model (parallel requests, shared state)

Where actual parallelism lives, and the two serialization points.

```mermaid
flowchart TB
  %% file: 05-concurrency-model
  subgraph Pool["Tomcat worker thread pool (~200 threads)"]
    T1[Thread 1]
    T2[Thread 2]
    T3[Thread N]
  end

  RL{{"LeakyBucketRateLimiter.tryAcquire()<br/>synchronized — one thread at a time"}}
  Work["Controller + JDBC<br/>(runs on the same worker thread)"]
  WLOCK[["SQLite single-writer lock<br/>writes serialized"]]

  T1 --> RL
  T2 --> RL
  T3 --> RL
  RL -->|"admitted"| Work
  Work --> WLOCK
```

**Takeaways**
- Parallelism = multiple worker threads, not async offloading. Each admitted
  request is handled start-to-finish on its own thread.
- Two serialization points under load: the `synchronized` bucket (tiny critical
  section) and SQLite's single-writer lock (writes are serialized; reads can
  overlap).
- Ideas to make the concurrent/limiter design more robust are in
  [`RATE_LIMITING.md`](RATE_LIMITING.md).
