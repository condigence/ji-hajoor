# One-shot job that gives a fresh Ji-Hajoor database its starter data:
# the service catalog (docker/seed-catalog.sql) and Pricing Engine parameters
# for every subcategory that has none. Safe to re-run: it never overwrites data
# an admin has changed. Build from the repo root:
#   docker build -f deploy/seed/Dockerfile -t ji-hajoor-seed .
FROM postgres:16-alpine
RUN apk add --no-cache curl jq
COPY docker/seed-catalog.sql /seed/seed-catalog.sql
COPY deploy/seed/seed.sh /seed/seed.sh
RUN chmod 755 /seed/seed.sh
ENTRYPOINT ["/seed/seed.sh"]
