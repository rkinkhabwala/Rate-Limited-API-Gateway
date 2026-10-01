# Rate-Limited API Gateway

A Spring Cloud Gateway + Redis service that sits in front of your APIs and does what Stripe, GitHub
and OpenAI do at their edge: **issue API keys, enforce per-plan rate limits and monthly quotas, and
meter usage for billing**. Limits hold across any number of gateway instances because all counters
live in Redis and are updated atomically.

```
client ──X-API-Key──▶ ┌──────────────── gateway ─────────────────┐ ──X-Client-Id──▶ upstream
                      │ Metering → Auth → RateLimit → route/proxy │
                      └──────────────────┬────────────────────────┘
                                         ▼
                                       Redis   (keys, token buckets, quotas, usage)
```

## The real-world problems it solves

| Problem | Naive approach breaks because… | This gateway |
|---|---|---|
| Who is calling? | Keys stored in plaintext leak with a DB dump | Only SHA-256 hashes stored; key shown once at creation |
| Traffic spikes | A per-instance in-memory counter lets N instances allow N× the limit | Shared token bucket in Redis |
| Race conditions | `GET count` then `SET count+1` lets parallel requests slip through | One Lua script: check + consume atomically |
| Plans & billing | "100 req/s" alone doesn't cap monthly cost | Burst + sustained rate + monthly quota per plan |
| Clients don't know their limits | They hammer until banned | `X-RateLimit-*`, `X-Quota-*`, `Retry-After` on every response |
| Secrets reaching backends | Upstreams log headers; keys end up in log search | Key stripped; trusted `X-Client-Id` added, spoofed values overwritten |
| Redis outage | Every API goes down with it | Configurable fail-open (default) or fail-closed |
| "Why was I charged?" | No per-day usage history | Per-client, per-day, per-route, per-status counters |

## Run it

Requirements: Java 21, Maven 3.9+, Docker (for Redis and the integration tests).

```bash
docker compose up -d                 # Redis on localhost:6379
mvn verify                           # unit tests always; integration tests need Docker
mvn spring-boot:run                  # gateway on http://localhost:8080
```

## Try it with curl

```bash
ADMIN='X-Admin-Token: change-me-in-production'

# 1. Create a client on the free plan (2 req/s, burst 5, 1000/month). The key is shown ONCE.
curl -s -X POST localhost:8080/admin/clients -H "$ADMIN" -H 'Content-Type: application/json' \
     -d '{"name":"Acme mobile app","plan":"free"}'
# → {"client":{"id":"cli_…",…},"apiKey":"gwk_…","warning":"Store this key now…"}

# 2. Call an API through the gateway (the demo route proxies to httpbin.org)
curl -i localhost:8080/api/httpbin/headers -H "X-API-Key: $KEY"
#   X-RateLimit-Limit: 5   X-RateLimit-Remaining: 4   X-Quota-Remaining: 999
#   httpbin echoes X-Client-Id / X-Client-Plan, and no X-Api-Key

# 3. Hammer it: after 5 quick calls you get 429 + Retry-After
for i in $(seq 1 8); do curl -s -o /dev/null -w '%{http_code} ' localhost:8080/api/httpbin/get -H "X-API-Key: $KEY"; done

# 4. Usage report, rotate the key, revoke the client
curl -s localhost:8080/admin/clients/$ID/usage?days=7 -H "$ADMIN"
curl -s -X POST localhost:8080/admin/clients/$ID/rotate-key -H "$ADMIN"
curl -s -X DELETE localhost:8080/admin/clients/$ID -H "$ADMIN"
```

Add your own backend by adding an entry under `apigw.routes` in `application.yml`; no code change.

## Configuration

```yaml
apigw:
  admin-token: ${APIGW_ADMIN_TOKEN:…}
  fail-open: true
  plans:
    free: { replenish-rate-per-second: 2, burst-capacity: 5, monthly-quota: 1000 }
  routes:
    - { id: orders, path: /api/orders/**, uri: http://orders-service:8080, strip-prefix: 1 }
```

## API

**Proxied traffic:** any configured route, with header `X-API-Key`.
Errors are Problem Details with a `code`: `MISSING_API_KEY`, `INVALID_API_KEY`, `RATE_LIMITED`,
`QUOTA_EXCEEDED`, `PLAN_NOT_CONFIGURED`, `RATE_LIMITER_UNAVAILABLE`.

**Admin** (header `X-Admin-Token`):

| Method | Path | |
|---|---|---|
| POST | `/admin/clients` | `{name, plan}` → client + plaintext key (once) |
| GET | `/admin/clients/{id}` | client details (never the key) |
| POST | `/admin/clients/{id}/rotate-key` | new key; old one stops working immediately |
| DELETE | `/admin/clients/{id}` | revoke |
| GET | `/admin/clients/{id}/usage?days=7` | quota status + daily breakdown by route and status |

## How it works

### Filter chain (lower order runs first)

| Order | Filter | Job |
|---|---|---|
| -300 | `MeteringFilter` | Wraps everything; after the response, records route + status (incl. 429s) |
| -200 | `ApiKeyAuthFilter` | Key → client via hash lookup; 401 or strip key + set `X-Client-Id` |
| -100 | `RateLimitFilter` | Runs `admit.lua`; adds limit headers; 429 / 503 / pass |
| … | Gateway routing | StripPrefix, proxy to upstream |

### The token bucket (`src/main/resources/scripts/admit.lua`)

Each client has a bucket holding up to `burst-capacity` tokens, refilled at `replenish-rate-per-second`.
A request takes one token. That gives clients a short burst allowance and a steady sustained rate.
The script also checks the monthly quota first and only counts the request once it's admitted, so
throttled requests don't burn quota. Design points worth noticing:

- **Atomic:** Redis runs a Lua script without interleaving other commands, so 20 parallel requests
  against a bucket of 3 get exactly 3 through (`RateLimitTest.parallelRequestsCannotSneakPastTheLimit`).
- **Redis time, not gateway time:** `redis.call('TIME')` means instances with drifting clocks agree.
- **Lazy refill:** no background job tops buckets up; the refill is computed from elapsed time on the
  next request. Idle buckets expire on their own.
- **Cluster-ready keys:** `apigw:rl:{cli_42}:bucket` and `…{cli_42}:quota:202611` share the `{cli_42}`
  hash tag, so both live in the same Redis Cluster slot (a multi-key script requires that).

### Why fail-open by default?

If Redis is down you choose between "every API is down" and "limits are briefly unenforced". Most
public APIs pick the second, alert loudly (`apigw.admission{outcome=unavailable}` metric), and accept
a few minutes of unmetered traffic. Payment or abuse-sensitive APIs may prefer `fail-open: false`.

### A reactive gotcha (see `ApiKeyAuthFilter`)

`chain.filter(exchange)` returns an *empty* `Mono<Void>` when it succeeds. So
`findClient().flatMap(c -> chain.filter(…)).switchIfEmpty(reject401)` sends a 401 after every
successful request. The filter wraps the lookup in `Optional` to keep "not found" separate from "done".

## Tests

| Test | Needs Docker | Covers |
|---|---|---|
| `RateLimitFilterTest` | no | 429 headers, quota `Retry-After`, fail-open vs fail-closed, unknown plan |
| `ApiKeysTest`, `RateLimiterUnitTest`, `UsageMeterStatusBucketTest` | no | key format/hash, Lua reply parsing, UTC month boundaries, hash tags |
| `AuthAndRoutingTest` | yes | 401s, header stripping/spoofing, revoke, rotate, admin token |
| `RateLimitTest` | yes | burst → 429 → refill, per-client isolation, 20 parallel requests |
| `QuotaAndMeteringTest` | yes | monthly quota, usage report, 5xx metering |

Integration tests run the real gateway against Redis in Testcontainers and a stub upstream built on the
JDK's `HttpServer`; they skip themselves when Docker isn't available.

## Practice exercises (roughly easiest → hardest)

1. **Per-route cost.** Make `/api/reports/**` cost 5 tokens instead of 1 (the script already takes a cost).
2. **Key cache.** Every request does 2 Redis reads to resolve the key. Add a Caffeine cache (30 s TTL) and
   decide how revocation should invalidate it across instances (hint: Redis pub/sub).
3. **Grace period on rotation.** Let the old key keep working for 24 h after rotation.
4. **Concurrency limit.** Add a second limiter: max N in-flight requests per client (a Redis counter
   incremented before proxying and decremented in `doFinally`). Think about crashes leaking slots.
5. **Quota alerts.** Publish an event when a client crosses 80% and 100% of quota, exactly once per month.
6. **Sliding window.** Implement a sliding-window-log limiter (sorted set) as an alternative strategy and
   compare memory use and accuracy against the token bucket.
7. **JWT clients.** Accept OAuth2 bearer tokens as well as API keys (`spring-boot-starter-oauth2-resource-server`),
   mapping a token claim to a plan.
8. **Billing export.** A nightly job that writes yesterday's usage for every client to Postgres or CSV.
9. **Load test.** k6 against two gateway instances behind nginx; verify the combined throughput per client
   stays at the plan limit and measure the latency Redis adds per request.
