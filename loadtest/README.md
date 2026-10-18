# Load tests

[k6](https://k6.io) scripts that drive the platform through the API gateway the way the three
apps do. k6 runs in Docker (`grafana/k6:1.0.0`), so nothing needs installing.

| Script | What it does |
|---|---|
| [`k6/marketplace.js`](k6/marketplace.js) | Three populations at once: **browse** (customers reading the catalog, pricing estimates and their history, with think time), **customers** (book a plumber, wait for a provider, pay once the job is done) and **providers** (poll offers, accept, share live location, upload before/after photos, complete). The booking journey crosses all five deployables and both Kafka hops (`BookingCreated` → dispatch, `PaymentCompleted` → booking). |
| [`k6/guardrails.js`](k6/guardrails.js) | The gateway's protections under a burst: per-user limit (100/min for a customer) answers 429 with `Retry-After` without touching another user, the 6th OTP request for one number in an hour is refused, and a missing token is a 401 under concurrency, never a 5xx. |
| [`run.sh`](run.sh) | Builds the images, starts and seeds the stack, runs a script, samples container CPU and memory. |

## Running

The target is the stack the **Booking flow** workflow uses ([`deploy/ci/run-stack.sh`](../deploy/ci/run-stack.sh)):
the Railway layout in Docker with the fixed OTP code `123456`, the payment simulator, the seeded
catalog and pricing, and the `owner` admin account. It needs about 6 GB of Docker memory; stop
any other local stack first.

```bash
mvn -q -DskipTests -Djacoco.skip=true package    # JDK 25: build the five deployables
bash loadtest/run.sh stack-up                    # images, start, health checks, seed (~6 min cold)

PROFILE=smoke bash loadtest/run.sh marketplace   # 1 min, a handful of users: checks the scripts
PROFILE=load  bash loadtest/run.sh marketplace   # 60 browsing + 12 booking customers, 8 providers, 4 min hold
PROFILE=stress bash loadtest/run.sh marketplace  # 150 / 30 / 12, 5 min hold
bash loadtest/run.sh guardrails                  # under a minute

bash loadtest/run.sh stats                       # CPU / memory of each container now
bash loadtest/run.sh stack-down                  # remove everything
```

`BROWSE_VUS`, `CUSTOMER_VUS`, `PROVIDERS` and `HOLD` override a profile. To aim at another
environment, run k6 yourself with `GATEWAY`, `OTP`, `ADMIN_USER` and `ADMIN_PASS`; it needs
`OTP_FIXED_CODE` and the payment simulator there, so never point it at production.

Each run writes the full k6 summary to `loadtest/results/marketplace-<profile>-<time>.json`
(ignored by git) and prints a short table of latencies, journey timings and 429s per endpoint.

### What the thresholds mean

| Threshold | Limit | Why |
|---|---|---|
| `http_req_failed` | < 1 % | Anything but 2xx/3xx is a failure, except 409 on an offer another provider took first and 404 on a location before the first update |
| browse p95 / p99 | 500 ms / 1.5 s | Catalog, estimate and history are what every app screen opens with |
| `POST /bookings`, confirmation p95 | 1.5 s | Synchronous calls to catalog, pricing, customer and promotion services |
| `POST /locations/{id}` p95 | 300 ms | Sent every 5 s by every provider on the way to a job |
| `journey_confirm_to_accepted_ms` p95 | 30 s | Outbox relay → Kafka → dispatch ranking → offer → provider polls (2 s) → accept |
| `journey_payment_to_completed_ms` p95 | 15 s | Payment → `PaymentCompleted` → booking status |
| `http_429` | 0 | The app-like traffic must stay inside the gateway's per-user limits |

### Keeping runs independent

`setup()` onboards fresh providers (OTP sign-up, profile, documents, admin approval, background
check) and `teardown()` suspends them. An approved provider who no longer answers would
otherwise receive the first offer batch of every later booking and hold it for the whole offer
window (`DISPATCH_OFFER_TIMEOUT_SECONDS`, 60 s): the same thing an idle real provider does (see
the findings below). Mobile numbers embed a per-run id, so reruns never meet the 5-per-hour OTP
throttle.

## Results, 2026-10-18

`load` profile on one laptop (Docker with 6 CPUs and 11 GB for the whole stack, so a floor rather
than a capacity figure), after the fixes in
[CODEBASE_REVIEW.md, Section 21](../CODEBASE_REVIEW.md#21-load-test-and-gap-review---2026-10-18):

| Measure | Result |
|---|---|
| Complete journeys | 82 in the 4-minute hold (3 unfinished, see below) |
| Requests | 12,487 at 26/s, 0.94 % unexpected statuses |
| Browse p95 / p99 | 21 ms / 52 ms |
| Create / confirm booking p95 | 114 ms / 65 ms |
| Location ping p95 | 76 ms |
| Payment p95 | 272 ms |
| Confirm → provider accepted | p50 4.1 s, p95 13.7 s |
| Payment → `PAYMENT_COMPLETED` | p50 1.0 s, p95 1.1 s |
| Errors in the services' logs | 0 |

Every latency threshold passed. The two that failed (`http_429`, `journeys_failed`) have one cause:
providers doing jobs back to back reach the gateway's 60-requests-a-minute provider limit (25
refusals). That limit is now `RATE_LIMIT_PROVIDER_PER_MINUTE`; choosing its value is open.

`guardrails.js`: 2,012 of 2,012 checks passed.

What the runs found, and what was fixed because of them: an idle provider holds an offer wave for
the whole 60 s window (no online/offline state yet); notification-service ran out of database
connections (fixed); hot polls had no index (fixed); busy providers reach their rate limit (open).
Details in Section 21.2.
