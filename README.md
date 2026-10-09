# Ji-Hajoor

Home-services platform: 20 Spring Boot services (`services/`), shared libraries
(`shared/`) and three frontends (`frontend/`). See `docs/` for architecture and
local setup.

## Deploying a backend service on Railway

The root `Dockerfile` builds the shared libraries and one service from a clean
checkout, and `railway.json` tells Railway to use it.

1. In the Railway service, leave **Root Directory** empty (repo root).
2. Add a variable `SERVICE` with the folder name under `services/`
   (e.g. `api-gateway`, `auth-service`). Defaults to `api-gateway`.
3. Add the service's runtime variables (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`,
   `REDIS_HOST`, `KAFKA_BOOTSTRAP_SERVERS`, JWT secret, … — see `.env.example`).

The app listens on Railway's `$PORT` automatically.
