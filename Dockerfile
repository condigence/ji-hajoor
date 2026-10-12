# Ji-Hajoor — one Dockerfile for every backend deployable, built from the repo
# root (Railway, Render, plain `docker build`).
#
# The backend runs as five deployables: four apps that each host a group of
# services in one JVM (apps/identity-app, jobs-app, money-app, engagement-app)
# and the API gateway. The per-deployable Dockerfiles under apps/ and
# services/api-gateway copy a jar built on the host; this one builds it inside
# the image from the Maven reactor, so it works from a clean git checkout.
#
# Pick the deployable with the APP build arg (on Railway: a service variable
# named APP, which Railway passes to the build):
#   identity-app | jobs-app | money-app | engagement-app | api-gateway
#
#   docker build --build-arg APP=jobs-app -t ji-hajoor-jobs .

ARG APP=api-gateway

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-25 AS build
ARG APP
WORKDIR /src
COPY pom.xml ./
COPY shared/ shared/
COPY services/ services/
COPY apps/ apps/
RUN case "$APP" in \
      api-gateway) module=services/api-gateway ;; \
      identity-app|jobs-app|money-app|engagement-app) module="apps/$APP" ;; \
      *) echo "Unknown APP '$APP'. Use identity-app, jobs-app, money-app, engagement-app or api-gateway."; exit 1 ;; \
    esac \
 && mvn -B -q -DskipTests -pl "$module" -am package \
 && cp "$module/target/$APP-1.0.0-SNAPSHOT.jar" /app.jar \
 && cd / && java -Djarmode=tools -jar /app.jar extract --destination /extracted

# ---------- runtime ----------
FROM eclipse-temurin:25-jre-alpine
# UID 999 as in the per-deployable images, so files on shared volumes stay writable.
RUN addgroup -S app && adduser -S -u 999 -G app app
WORKDIR /app
# Dependencies first: they change less often than the application classes.
COPY --from=build /extracted/lib/ lib/
COPY --from=build /extracted/app.jar app.jar
# Maps PORT / DATABASE_URL / REDIS_URL (Railway & co.) onto the services' own
# settings, then runs the jar. Explicitly set variables always win.
COPY deploy/entrypoint.sh /app/entrypoint.sh
RUN chmod 755 /app/entrypoint.sh
USER app
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
