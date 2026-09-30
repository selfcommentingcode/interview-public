# Testing the CRM API (Java) end-to-end

Everything below assumes you're in the `java/` directory and using **Git Bash**.

## Unit tests & coverage

Run the JVM unit tests (no server needed):

```bash
cd java
./mvnw test
```

**8 tests, all green.** They exercise the rate limiter directly:

| Test class | What it exercises |
|------------|-------------------|
| `LeakyBucketRateLimiterTest` | The algorithm — admit up to capacity, reject overflow, config-driven capacity, steady leak (1 slot / 6 s), full recovery after a minute, and idle-time clamping. A controllable clock makes it deterministic (no `Thread.sleep`). |
| `RateLimitFilterTest` | The servlet filter — admitted requests pass down the chain; rejected ones short-circuit with `429` + the JSON error body. |

**Coverage (JaCoCo).** `./mvnw test` writes a report to `target/site/jacoco/index.html`.
The rate limiter — the code added in this change — is fully covered:

| Class | Line | Branch |
|-------|------|--------|
| `LeakyBucketRateLimiter` | 100% (15/15) | 100% (2/2) |
| `RateLimitFilter` | 100% (11/11) | 100% (2/2) |

*(Overall project line coverage is ~17% — the pre-existing CRM controllers, repositories, and models have no unit tests and are outside this assignment's scope.)*

## What's already running
- Server: <http://localhost:8080>, **seeded** (50 contacts, 5 files, activity log).
- Rate limit: **default 10 req/min** (global leaky bucket).
- Auth: bearer token = any valid email, e.g. `user@example.com`.

## Postman — import & run (2 min)
Files live in `java/postman/`:
- `CRM-API.postman_collection.json`
- `CRM-API.postman_environment.json`

1. Postman → **Import** → drop both files.
2. Top-right environment selector → pick **CRM API - Local**.
3. Run folders **individually** (not the whole collection — the two folders need different limits):

| Folder | Server mode needed | What it proves |
|--------|--------------------|----------------|
| `0 · Sanity` | any | `/health` → 200 `ok` |
| `1 · Rate Limit` | **limit = 10** (current) | 12 rapid `/health` → **10×200 then 429** |
| `2 · Functional E2E` | **raised limit** (see below) | CRUD + import/export + files + report |
| `3 · Auth` | any | valid token → 200; missing/bad → 401 |

   To run a folder: hover it → **Run** (opens Collection Runner) → **Run CRM API…**.

### The rate-limit folder needs a fresh bucket
Run `1 · Rate Limit` **first**, before anything else. If you've hit the server in
the last minute, wait ~60s so the bucket is full again — otherwise you'll see the
429s start before request #11.

### The functional folder needs a raised limit
The global 10/min cap will throttle a full CRUD run, so raise it first:
```bash
# stop the current server:
taskkill //F //PID $(netstat -ano | grep ':8080 ' | grep LISTENING | awk '{print $NF}' | head -1)
# start with a high limit:
./mvnw -q spring-boot:run -Dspring-boot.run.arguments="--app.rate-limit.requests-per-minute=1000"
```
Then run `2 · Functional E2E`. (For the **Upload file** request, open its Body →
form-data and attach any local file before sending.)

To switch back to the rate-limit demo, stop the server and start plain:
```bash
./mvnw -q spring-boot:run     # default limit = 10
```

## curl quickstart (no Postman)
```bash
BASE=http://localhost:8080; AUTH="Authorization: Bearer user@example.com"

# 1) Rate limiter — 12 rapid calls → ten 200s then 429 (run on a fresh bucket):
for i in $(seq 1 12); do curl -s -o /dev/null -w "#$i -> %{http_code}\n" $BASE/health; done

# 2) Functional (raise the limit first, see above):
curl -s -X POST $BASE/api/contacts -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"first_name":"Jane","last_name":"Doe","email":"jane@example.com","phone":"555","company":"Acme"}'
curl -s "$BASE/api/contacts?limit=5" -H "$AUTH"
curl -s $BASE/api/reports/activity -H "$AUTH"

# 3) Auth negatives:
curl -s -o /dev/null -w "no token -> %{http_code}\n"  $BASE/api/contacts
curl -s -o /dev/null -w "bad token -> %{http_code}\n" $BASE/api/contacts -H "Authorization: Bearer nope"
```

## (Optional) run the collection headless with Newman
```bash
npx -y newman run postman/CRM-API.postman_collection.json \
  -e postman/CRM-API.postman_environment.json \
  --folder "1 · Rate Limit"
```

## Notes
- **Seeding** works via `make seed` (or `./mvnw spring-boot:run -Dspring-boot.run.arguments="--app.seed=true"`).
  `SeedCommand` runs before `StartupCheck`, so a fresh DB seeds cleanly and exits. For a
  smaller set: `./mvnw spring-boot:run -Dspring-boot.run.arguments="--app.seed=true --contacts=50 --files=5"`.
- Rate limit is **global** (all clients share one bucket) and applies to **every path,
  including `/health`** — so any request, authenticated or not, counts against it.
