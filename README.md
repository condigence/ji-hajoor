# Ji-Hajoor

Ji-Hajoor is an on-demand home-services marketplace. Customers book verified workers for
scheduled or emergency jobs (plumbing, electrical, AC, cleaning). Workers are onboarded
through document verification and a background check, then matched to jobs by a dispatch
engine. Service agencies run their own teams of workers on the platform.

Live: [jihajoor.com](https://jihajoor.com) — **Ji-Hajoor**, for customers;
[partner.jihajoor.com](https://partner.jihajoor.com) — **Ji-Hajoor Pro**, for workers and agencies'
workers; [admin.jihajoor.com](https://admin.jihajoor.com) — **Ji-Hajoor Admin**, for Ji-Hajoor staff
and agency administrators (**My agency**).

> **Naming.** The apps and these docs say *agency* and *worker* (customers see *professional*); the
> full conventions are in [docs/BRAND_AND_MARKETING.md](docs/BRAND_AND_MARKETING.md). The code, APIs and database
> keep their original names: an agency is a `Tenant` (`/admin/tenants`, `/tenant/**`,
> `provider.tenant`) and a worker is a `Provider` / `SERVICE_PROVIDER` (`/providers/**`).

## Who uses it

| Role | App | What they do |
|---|---|---|
| Customer | Ji-Hajoor (customer app) | Sign in with mobile OTP, email or Google; book, track, chat, pay (Razorpay), review, raise complaints, download invoices |
| Worker / agent | Ji-Hajoor Pro (partner app) | Register directly, upload verification documents, set services, area and availability, take jobs, record work (photos, parts, quotes), see earnings and request settlements |
| Agency administrator | Ji-Hajoor Admin ("My agency") | Apply to become an agency, invite workers, assign requests no independent worker took, follow its jobs, see its earnings |
| Ji-Hajoor staff | Ji-Hajoor Admin | Approve agencies and workers, set each agency's share, run bookings, payments, complaints, reviews, coupons, dispatch rules, settings and the audit log (roles: `SUPER_ADMIN`, `ADMIN`, `FINANCE_ADMIN`, `DISPATCHER`, `SUPPORT_AGENT`) |

### Two supply channels

Workers reach customers in two ways, so the platform never depends on agencies alone:

1. **Independent workers** register themselves in the partner app, are verified by Ji-Hajoor and
   receive job offers directly from the dispatch engine.
2. **Agencies** apply in the admin portal; once Ji-Hajoor approves them they build a team by
   **inviting** workers by mobile number. A worker joins only by accepting the invitation in the
   partner app, which shows the agency's earnings share first, and can later leave to work
   independently again. (Ji-Hajoor staff can still attach a worker directly.) When no independent
   worker accepts a request, it waits in the covering agencies' assignment queue and an agency
   assigns one of its verified workers.

### Money: commission and agency share

For each paid job:

```
gross (what the customer pays for the service)
  − platform fee (platformFeeRate per service, 10% in the starter pricing)  → Ji-Hajoor
  = worker's net
      − agency share (0–50%, set per agency by Ji-Hajoor; 0 by default)  → the agency
      = credited to the worker's wallet
```

Example: a ₹1,000 job with a 10% fee and a 10% agency share credits ₹100 to Ji-Hajoor, ₹90 to
the agency and ₹810 to the worker. An independent worker keeps the whole net (₹900). The
worker's earnings list shows the agency share as its own line; the agency sees every job's split on
its Earnings page. Each booking is credited once, however many times the payment service retries.

### Reviews that build trust

Reviews are how good workers win more jobs and how customers choose with confidence:

- **Asked at the right moment.** When a job is paid, the receipt asks the customer to rate it, the
  booking shows a one-tap "Rate this job" card, and the home screen lists jobs still waiting for a
  rating. If a job is not rated within 24 hours, one reminder goes out (push, SMS and in-app); never
  more than one.
- **Public worker page.** Every verified worker has a page at `/workers/<id>` in the customer app:
  skills, years of experience, agency, rating with a 5-to-1 star breakdown, the four aspect scores
  (quality, timeliness, behaviour, price transparency) and recent reviews. It opens without signing
  in, never shows who wrote a review, and only exists for workers Ji-Hajoor has approved.
- **Workers answer.** In the partner app, *My reviews* lists every review a worker received; they
  can reply once (and edit it), and the reply shows under the review on their public page.
- **Share your profile.** The partner app's profile has a "Share my profile" card (the phone's share
  sheet, e.g. WhatsApp, or copy link), so a worker can send their rated page to new customers and bring them to the
  platform. Agencies benefit through their workers' ratings.

## Repository layout

| Path | Contents |
|------|----------|
| [`pom.xml`](pom.xml) | Maven reactor and parent of every backend module: Java 25, Spring Boot 4.1 |
| [`services/`](services/) | 20 Spring Boot services: the API gateway and 19 domain services (plain jars) |
| [`apps/`](apps/) | The four deployables that host the domain services, several per JVM: `identity-app`, `jobs-app`, `money-app`, `engagement-app` |
| [`shared/`](shared/) | Shared Maven libraries: observability, security, resilience, transactional outbox, `config.app_config` settings, and the service host the deployables use |
| [`frontend/`](frontend/) | Three React 19 + TypeScript 6 + Vite 8 apps (Node 24): `customer-app`, `provider-app`, `admin-portal` |
| [`docker/`](docker/) | Database init and seed scripts, smoke tests, local-infra helpers |
| [`helm/`](helm/) | Helm chart (`jihajoor-service`) and values for each of the five deployables |
| [`infra/terraform/`](infra/terraform/) | AWS infrastructure: VPC, EKS, RDS, MSK, ElastiCache, KMS, IAM |
| [`infra/k8s/`](infra/k8s/) | Cluster-level manifests (observability) |
| [`docs/`](docs/) | Architecture, API contracts and local-run guide |
| [`loadtest/`](loadtest/) | k6 load tests (marketplace journey, gateway guardrails), run in Docker |

## Services and deployables

The 20 services run as five deployables (five JVMs, five images) instead of twenty. A
deployable under `apps/` starts each of its services as its own Spring application context
inside one JVM (`shared/jihajoor-service-host`), each on its own port with its own
configuration (`<service>.yml`), database schema and beans, so a service can still be moved
to its own deployable without code changes.

| Deployable | Services (port) |
|------------|-----------------|
| api-gateway | api-gateway (8080) |
| identity-app | auth-service (8081), customer-service (8082), provider-service (8083), verification-service (8094) |
| jobs-app | booking-service (8084), catalog-service (8085), pricing-engine (8086), dispatch-engine (8087), location-service (8092), chat-service (8093), promotion-service (8098) |
| money-app | payment-service (8088), invoice-service (8089) |
| engagement-app | notification-service (8090), complaint-service (8091), admin-service (8095), reporting-service (8096), rating-review-service (8097), outbox-processor (8099) |

An environment variable meant for one hosted service only is prefixed with its name, e.g.
`PRICING_ENGINE__LOGGING_LEVEL_ROOT`; unprefixed variables reach every service in the
deployable. Platform settings shared by all of them (such as the web apps' public URLs) are
rows of the `config.app_config` table; see [ARCHITECTURE.md](docs/ARCHITECTURE.md#settings-in-the-database-configapp_config).

The services share one PostgreSQL database with a schema per service (migrated by Flyway at
startup), communicate asynchronously over Kafka through a transactional outbox, and use Redis
for caching and rate limiting. All client traffic goes through the gateway.

The web apps run on `5173` (customer), `5174` (provider) and `5175` (admin). The customer and
provider apps also ship as Android and iOS apps via Capacitor; see
[`frontend/MOBILE.md`](frontend/MOBILE.md).

## Running locally

Locally the services and web apps run in Docker Compose, while PostgreSQL, Kafka and Redis run
on the host machine. Full setup, including the one-time infrastructure configuration, seeding
and test users, is in [`docs/LOCAL_ACCESS.md`](docs/LOCAL_ACCESS.md). In short:

```bash
cp .env.example .env            # first time only

# Build the deployables (JDK 25; one reactor build from the repository root)
mvn -q -DskipTests -Djacoco.skip=true package

# Start Kafka on the host first, then the stack
docker compose -f docker-compose.core.yml up -d --build --wait
```

The images copy the prebuilt jar from `apps/<deployable>/target/` (and
`services/api-gateway/target/`), so rebuild before `--build` after changing a service.

To work on a web app on its own:

```bash
cd frontend/customer-app
npm install
npm run dev
```

## Deployment

Production targets AWS: Terraform provisions the platform (EKS, RDS PostgreSQL, MSK, ElastiCache)
and each of the five deployables is deployed to EKS with the Helm chart in [`helm/`](helm/), configured through
per-environment values and Secrets. See [`infra/terraform/README.md`](infra/terraform/README.md).

### Railway

The live Ji-Hajoor environment runs on Railway from this repository's `main` branch:

| Railway service | Source | Key variables |
|---|---|---|
| `identity-app`, `jobs-app`, `money-app`, `engagement-app`, `api-gateway` | root [`Dockerfile`](Dockerfile) | `APP` (which deployable to build), `PORT` (first hosted service's port: 8081 / 8084 / 8088 / 8095 / 8080), `JAVA_OPTS` (heap cap) |
| `ji-hajoor` (Admin Portal), `customer-app`, `provider-app` | `frontend/<app>` | `API_GATEWAY_URL` (gateway's private URL) |
| `seed` | `deploy/seed` | run-once: starter catalog, pricing, and the app URLs in `config.app_config` |
| `Postgres`, `Redis`, `kafka` | images | — |

- The apps get `DATABASE_URL`, `REDIS_URL` and `KAFKA_BOOTSTRAP_SERVERS` from Railway references; [`deploy/entrypoint.sh`](deploy/entrypoint.sh) maps them onto the Spring settings.
- Secrets (`JWT_SECRET`, `INTERNAL_API_KEY`, `KMS_*`, webhook and simulator secrets) are shared variables.
- Services find each other at `http://${{<app>.RAILWAY_PRIVATE_DOMAIN}}:<service port>`, using the port table above.
- SMS settings live on `identity-app`: `SMS_PROVIDER=msg91`, `MSG91_AUTH_KEY` and `MSG91_OTP_TEMPLATE_ID`. `OTP_FIXED_CODE` is a test code that works **only** for the numbers in `OTP_FIXED_CODE_NUMBERS` (default: the four seeded test customers and workers, `+919000090001`, `+919000090002`, `+919000090011`, `+919000090012`; an entry ending in `*` is a prefix). Every other number gets a real SMS, and staff accounts never sign in with it. Remove `OTP_FIXED_CODE` to switch test sign-in off entirely.
- Email: on `identity-app` set `EMAIL_PROVIDER=smtp`, and on `engagement-app` `NOTIFICATION_EMAIL_PROVIDER=smtp`, both with `SMTP_HOST`, `SMTP_PORT` (587), `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_STARTTLS=true` and `EMAIL_FROM` (a sender the SMTP provider has verified). Never `SPRING_MAIL_*`: it would reach every service in the JVM.
- Verification documents (`identity-app`): `DOCUMENTS_BUCKET`, `DOCUMENTS_S3_REGION`, `DOCUMENTS_S3_ACCESS_KEY_ID` and `DOCUMENTS_S3_SECRET_ACCESS_KEY` (plus `DOCUMENTS_S3_ENDPOINT`), as for photos; without a bucket they go to the container's disk and are lost on redeploy.
- Job photos (`jobs-app`): `MEDIA_BUCKET`, `MEDIA_S3_REGION`, `MEDIA_S3_ACCESS_KEY_ID` and `MEDIA_S3_SECRET_ACCESS_KEY` (plus `MEDIA_S3_ENDPOINT` for an S3-compatible store) put them in S3. Without a bucket they go to the container's disk, which Railway discards on redeploy. Remove any `MEDIA_STORAGE_PROVIDER=local` so the default `auto` applies.
- `engagement-app` needs `PAYMENT_SERVICE_URL` (money-app's private domain, port 8088) for complaint refunds and `PROVIDER_SERVICE_URL` (identity-app, port 8083) for provider ratings.
- Chat uses the shared Redis (`CHAT_CLUSTER=redis`, the default), so `jobs-app` can run more than one replica.
- Gateway rate limits: per user `RATE_LIMIT_CUSTOMER_PER_MINUTE` (100) and `RATE_LIMIT_PROVIDER_PER_MINUTE` (60); per client IP for signed-out traffic `RATE_LIMIT_ANONYMOUS_PER_IP_PER_MINUTE` (300) and for sign-in, sign-up and password calls `RATE_LIMIT_CREDENTIAL_PER_IP_PER_MINUTE` (30).
- Runtime settings (app URLs and other `jihajoor.*` keys) live in the `config.app_config` table and can be changed without a redeploy. Environment variables override them.
- The "Booking flow" workflow ([`deploy/ci`](deploy/ci)) runs this same layout in Docker and drives a full booking through it on every push.

## Technology choices

The stack stays as it is: it already covers everything the product needs, and rewriting working
software would add risk without adding features.

| Layer | Used here | Instead of | Why |
|---|---|---|---|
| Web apps | React 19 + TypeScript + MUI, Vite | Angular | Three working apps with tests; React has the larger hiring pool in India |
| Mobile | The same React apps packaged with Capacitor (Android, iOS) | Flutter / React Native | One codebase for web and mobile; move to React Native only if native performance is ever needed |
| Backend | Spring Boot 4 / Java 25, run as a **modular monolith**: 20 services in 5 deployables | Microservices from day one | Each service has its own schema and can be split out later without code changes |
| Data | PostgreSQL (schema per service), Redis, Kafka with a transactional outbox | — | As recommended |
| Auth | JWT (HMAC), mobile OTP (MSG91), email, Google and Apple sign-in, staff by invitation | — | As recommended |
| Payments | Razorpay (orders, checkout, webhooks, refunds) | — | As recommended |
| Hosting | Railway now (Docker); AWS (EKS, RDS, MSK) prepared in `infra/terraform` and `helm/` | — | Railway is cheapest while small; the AWS path is ready when traffic grows |

## Security

What protects the platform today, by layer. No system can promise it cannot be hacked; this is
defence in depth, plus the checks that stop regressions.

- **Transport and browser:** HTTPS only (http is redirected, and HSTS makes browsers remember it).
  Every page carries a strict Content-Security-Policy (only this site's scripts plus Google sign-in
  and Razorpay checkout), `X-Frame-Options: DENY`, `nosniff`, a Referrer-Policy and a
  Permissions-Policy. Metrics, health and internal paths are not reachable from the internet.
- **Identity:** short-lived JWT access tokens checked at the gateway (introspection) and again in
  every service; refresh-token rotation, and in the web apps the refresh token lives in an
  `HttpOnly; Secure; SameSite=Strict` cookie scoped to `/api/auth`, so page scripts can never read
  it; role rules on every endpoint (`RbacEnforcementFilter`)
  plus ownership checks, so a user can only read or change their own bookings, payments, documents
  and agency. Agencies only ever see their own team, queue and earnings.
- **Passwords:** stored as BCrypt hashes, never in plain text. New passwords must be at least 10
  characters with a letter and a digit, and are refused when they are common (also in
  `P@ssw0rd`-style spellings), keyboard or number sequences, mostly one character, or contain the
  person's name or email.
- **Brute force:** OTP codes expire, lock after 5 wrong tries and are limited to 5 requests per
  number per hour; passwords lock after repeated failures; the gateway also limits each IP's
  sign-in attempts and signed-out traffic, and each user's request rate. Limits key on the
  visitor's real address from Railway's edge (`X-Real-IP`), which a client cannot forge.
- **Input:** a WAF filter at the gateway rejects common injection and traversal patterns; request
  bodies are validated; uploads are judged by their bytes, not their declared type (photos must be
  real JPEG, PNG or video; documents must be an image or a PDF) and are served back with `nosniff`
  and safe content types. Profile photo and icon links must be `https://` or a same-site path, so
  no `javascript:` or `data:` link can reach a page. The web servers' access logs hide query
  strings, so no code or token is ever written to a log.
- **Money:** Razorpay signatures are verified on checkout and webhooks; each booking is credited
  once (unique indexes); only finance roles can refund or settle.
- **Secrets:** kept in Railway variables (or AWS Secrets Manager), never in the repository;
  `.env.example` holds placeholders only.
- **CI:** the Security workflow blocks a commit that adds a secret (gitleaks) and reports known
  vulnerabilities in Maven and npm dependencies and Docker images (Trivy, npm audit) on every
  change and weekly.

Still to do (operational): turn off `PAYMENT_SIMULATOR_ENABLED` and set the Razorpay live keys
before taking real money, align payment-service's default 20% fee with the per-service platform fee
(see [docs/REVENUE_MODEL.md](docs/REVENUE_MODEL.md)), rotate `JWT_SECRET` and the test-staff password before real customers
arrive, remove `OTP_FIXED_CODE` once MSG91 delivery is confirmed, keep the Railway gateway without
a public domain, and act on the weekly vulnerability report.

## Documentation

Everything lives in [`docs/`](docs/), with a PDF of each deck and guide in [`docs/pdf/`](docs/pdf/)
for sharing. The whole set is also bundled as one review pack:
[`docs/pdf/JiHajoor_Documentation_Pack.pdf`](docs/pdf/JiHajoor_Documentation_Pack.pdf).

**How to use Ji-Hajoor** (share these with users):

- [Customer Guide](docs/guides/CUSTOMER_GUIDE.md): book, track, pay, rate
- [Worker Guide — Ji-Hajoor Pro](docs/guides/WORKER_GUIDE.md): verification, jobs, earnings, reviews, sharing your profile
- [Agency Guide](docs/guides/AGENCY_GUIDE.md): apply, build a team, assign requests, earnings
- [Staff Guide — Ji-Hajoor Admin](docs/guides/ADMIN_GUIDE.md): roles, daily checklist, every admin page

**Business:**

- [Pitch deck](docs/JiHajoor_Pitch_Deck.pptx)
- [Revenue model](docs/REVENUE_MODEL.md) and [deck](docs/JiHajoor_Revenue_Model.pptx)
- [Brand, naming and marketing](docs/BRAND_AND_MARKETING.md) and [deck](docs/JiHajoor_Brand_and_Marketing.pptx)

**Technology:**

- [Tech stack and security deck](docs/JiHajoor_Tech_Stack.pptx)
- [Architecture and diagrams](docs/ARCHITECTURE.md)
- [Architecture deck (PowerPoint)](docs/JiHajoor_Architecture.pptx) and [design review deck](docs/JiHajoor_Design_Deck.pptx)
- [API contracts](docs/API_CONTRACTS.md)
- [Running locally, test users and URLs](docs/LOCAL_ACCESS.md)
- [Mobile builds](frontend/MOBILE.md)
- [Load tests and latest results](loadtest/README.md)
- [Codebase review findings](CODEBASE_REVIEW.md)

## Signing in locally

All three apps accept email and password; the customer and provider apps also take mobile OTP
and Google. The seeded test accounts sign in as `<username>@jihajoor.local` (for example
`customer@jihajoor.local`, `provider@jihajoor.local`, `admin@jihajoor.local`) with the password
from `DEV_SEED_PASSWORD` in `.env`. New customers and providers can sign up with an emailed
code, staff join by invitation, and agencies register themselves for platform-admin approval
and then invite workers.
Locally, emailed codes and invitation links are written to `docker/dev-mail/dev-mail.log` and
OTP codes to `docker/dev-sms/dev-sms.log`. See
[`docs/LOCAL_ACCESS.md`](docs/LOCAL_ACCESS.md).

## What is real and what is stubbed

Real integrations: Google and Apple sign-in; Razorpay payments and refunds (test mode locally,
with the keys in `.env`), the only payment gateway; MSG91 for sign-in OTPs; email over SMTP
(Amazon SES in AWS; locally the dev mail log, or Mailpit with `docker compose --profile mail`);
and S3 for job photos and provider verification documents when a bucket is set and reachable
(local disk otherwise). Complaint refunds move money through payment-service (approved, and
re-sent if the outcome is unknown, from the Admin Portal), reviews update provider ratings, and
customers download their invoice PDF from the booking.

Still stand-ins: push notifications (no device tokens), background checks (an admin records the
result in the Admin Portal's Verification Queue), invoice PDF storage (in memory, re-rendered
from the invoice row on demand) and the Admin dashboard's metrics (zeros). Agency payouts are a ledger only: the agency's share is recorded, and paying it out is
still done outside the platform. The architecture document marks each one.
