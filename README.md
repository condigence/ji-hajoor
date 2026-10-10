# Ji-Hajoor

Home-services platform: 20 Spring Boot services (`services/`), shared libraries
(`shared/`) and three frontends (`frontend/`). See `docs/` for architecture and
local setup.

## Deploying on Railway

Every backend service builds from the root `Dockerfile` (choose it with the
`SERVICE` variable); `deploy/entrypoint.sh` turns Railway's `PORT`,
`DATABASE_URL` and `REDIS_URL` into the services' own settings. The
**Railway smoke test** workflow builds these images and signs in through
portal -> gateway -> auth-service on every relevant change.

Railway reference syntax below: `${{Postgres.DATABASE_URL}}` etc. Replace
`auth-service`, `admin-service`, `api-gateway` with your Railway service names.

**Project-level shared variable**
- `JWT_SECRET` = a random 64-character hex string (`openssl rand -hex 32`)

**Databases**: add **PostgreSQL** and **Redis** to the project.

| Service | Root dir | Variables |
|---|---|---|
| auth-service | *(empty)* | `SERVICE=auth-service`, `PORT=8081`, `DATABASE_URL=${{Postgres.DATABASE_URL}}`, `REDIS_URL=${{Redis.REDIS_URL}}`, `JWT_SECRET=${{shared.JWT_SECRET}}`, `BOOTSTRAP_ADMIN_USERNAME`, `BOOTSTRAP_ADMIN_PASSWORD` (12+ chars), `BOOTSTRAP_ADMIN_MOBILE` (e.g. `+919876543210`) |
| admin-service | *(empty)* | `SERVICE=admin-service`, `PORT=8095`, `DATABASE_URL=${{Postgres.DATABASE_URL}}`, `JWT_SECRET=${{shared.JWT_SECRET}}` |
| api-gateway | *(empty)* | `SERVICE=api-gateway`, `PORT=8080`, `REDIS_URL=${{Redis.REDIS_URL}}`, `JWT_SECRET=${{shared.JWT_SECRET}}`, `AUTH_SERVICE_URI=http://${{auth-service.RAILWAY_PRIVATE_DOMAIN}}:8081`, `AUTH_INTROSPECT_URI=http://${{auth-service.RAILWAY_PRIVATE_DOMAIN}}:8081/auth/introspect`, `ADMIN_SERVICE_URI=http://${{admin-service.RAILWAY_PRIVATE_DOMAIN}}:8095` |
| admin-portal | `frontend/admin-portal` | `API_GATEWAY_URL=http://${{api-gateway.RAILWAY_PRIVATE_DOMAIN}}:8080` |

Only the admin portal needs a public domain. After the first sign-in, delete
the three `BOOTSTRAP_ADMIN_*` variables (the account stays; the seeder never
changes an existing account).

Further services follow the same pattern: `SERVICE=<folder>`, its `PORT`,
`DATABASE_URL`/`REDIS_URL` as needed, `JWT_SECRET`, and a matching
`<NAME>_URI` on the gateway (see `services/api-gateway/src/main/resources/application.yml`).
