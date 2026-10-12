# Multi-stage build for the customer-app SPA: Node build -> static nginx serve.
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Build with the default same-origin /api base; nginx proxies /api to the gateway.
RUN npm run build

FROM nginx:1.30-alpine AS runtime
# Rendered to /etc/nginx/conf.d/default.conf at start-up (see the template).
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY docker-entrypoint.d/15-ji-hajoor-env.envsh /docker-entrypoint.d/15-ji-hajoor-env.envsh
# The entrypoint only sources .envsh files that are executable.
RUN chmod +x /docker-entrypoint.d/15-ji-hajoor-env.envsh
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
