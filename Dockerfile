# Ji-Hajoor — single Dockerfile for deploying any backend service from the
# repo root (Railway, Render, plain `docker build`).
#
# The per-service Dockerfiles under services/*/ only copy a jar that was built
# on the host beforehand, so they cannot build from a clean git checkout. This
# one compiles the shared libraries and the chosen service inside the image.
#
# Pick the service with the SERVICE build arg. On Railway, add a service
# variable named SERVICE (e.g. SERVICE=auth-service); Railway passes service
# variables to the build as build args.
#
#   docker build --build-arg SERVICE=auth-service -t ji-hajoor-auth .

ARG SERVICE=api-gateway

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /src

# 1. Shared libraries -> local ~/.m2 (each service depends on them).
COPY shared/ shared/
RUN for lib in shared/*/; do \
      mvn -B -q -f "$lib/pom.xml" -DskipTests install || exit 1; \
    done

# 2. The selected service.
COPY services/${SERVICE}/ services/${SERVICE}/
RUN test -f "services/${SERVICE}/pom.xml" \
      || { echo "Unknown SERVICE '${SERVICE}'. Valid values: $(ls services | tr '\n' ' ')"; exit 1; } \
 && mvn -B -q -f "services/${SERVICE}/pom.xml" -DskipTests package \
 && cp "services/${SERVICE}/target/${SERVICE}-1.0.0-SNAPSHOT.jar" /app.jar

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd -r app && useradd -r -g app app
COPY --from=build /app.jar app.jar
# Maps PORT / DATABASE_URL / REDIS_URL (Railway & co.) onto the services' own
# settings, then runs the jar. Explicitly set variables always win.
COPY deploy/entrypoint.sh /app/entrypoint.sh
RUN chmod 755 /app/entrypoint.sh
USER app
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
