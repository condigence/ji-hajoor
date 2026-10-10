# Multi-stage build for the customer-app SPA: Node build -> static nginx serve.
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Link to the provider app (shown as "Become a professional"). Baked in at build
# time; on Railway set VITE_PROVIDER_APP_URL on the service and it arrives here.
ARG VITE_PROVIDER_APP_URL=""
# Build with the default same-origin /api base; nginx proxies /api to the gateway.
# An empty provider URL is left unset so the app's own default applies.
RUN if [ -n "$VITE_PROVIDER_APP_URL" ]; then export VITE_PROVIDER_APP_URL; else unset VITE_PROVIDER_APP_URL; fi \
 && npm run build

FROM nginx:1.27-alpine AS runtime
# Rendered to /etc/nginx/conf.d/default.conf at start-up (see the template).
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY docker-entrypoint.d/15-ji-hajoor-env.envsh /docker-entrypoint.d/15-ji-hajoor-env.envsh
# The entrypoint only sources .envsh files that are executable.
RUN chmod +x /docker-entrypoint.d/15-ji-hajoor-env.envsh
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
