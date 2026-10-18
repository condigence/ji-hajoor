# Ji-Hajoor

Ji-Hajoor is an on-demand home-services marketplace. Customers book verified providers for
scheduled or emergency jobs (plumbing, electrical, AC, cleaning). Providers are onboarded
through document verification and a background check, then matched to jobs by a dispatch
engine. Service agencies (tenants) can run their own teams of providers on the platform.

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
- SMS settings live on `identity-app`: `SMS_PROVIDER=msg91`, `MSG91_AUTH_KEY` and `MSG91_OTP_TEMPLATE_ID`. `OTP_FIXED_CODE` puts sign-in into test mode: every OTP is that code and no SMS is sent. Remove it to go live.
- Email: on `identity-app` set `EMAIL_PROVIDER=smtp`, and on `engagement-app` `NOTIFICATION_EMAIL_PROVIDER=smtp`, both with `SMTP_HOST`, `SMTP_PORT` (587), `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_STARTTLS=true` and `EMAIL_FROM` (a sender the SMTP provider has verified). Never `SPRING_MAIL_*`: it would reach every service in the JVM.
- Verification documents (`identity-app`): `DOCUMENTS_BUCKET`, `DOCUMENTS_S3_REGION`, `DOCUMENTS_S3_ACCESS_KEY_ID` and `DOCUMENTS_S3_SECRET_ACCESS_KEY` (plus `DOCUMENTS_S3_ENDPOINT`), as for photos; without a bucket they go to the container's disk and are lost on redeploy.
- Job photos (`jobs-app`): `MEDIA_BUCKET`, `MEDIA_S3_REGION`, `MEDIA_S3_ACCESS_KEY_ID` and `MEDIA_S3_SECRET_ACCESS_KEY` (plus `MEDIA_S3_ENDPOINT` for an S3-compatible store) put them in S3. Without a bucket they go to the container's disk, which Railway discards on redeploy. Remove any `MEDIA_STORAGE_PROVIDER=local` so the default `auto` applies.
- `engagement-app` needs `PAYMENT_SERVICE_URL` (money-app's private domain, port 8088) for complaint refunds and `PROVIDER_SERVICE_URL` (identity-app, port 8083) for provider ratings.
- Chat uses the shared Redis (`CHAT_CLUSTER=redis`, the default), so `jobs-app` can run more than one replica.
- Runtime settings (app URLs and other `jihajoor.*` keys) live in the `config.app_config` table and can be changed without a redeploy. Environment variables override them.
- The "Booking flow" workflow ([`deploy/ci`](deploy/ci)) runs this same layout in Docker and drives a full booking through it on every push.

## Documentation

- [Architecture and diagrams](docs/ARCHITECTURE.md)
- [Architecture deck (PowerPoint)](docs/JiHajoor_Architecture.pptx)
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
code, staff join by invitation, and agencies register themselves for platform-admin approval.
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
from the invoice row on demand) and the Admin dashboard's metrics (zeros). The architecture
document marks each one.
