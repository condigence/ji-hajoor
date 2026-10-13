# One-shot job that gives a fresh Ji-Hajoor database its starter data:
# the service catalog (docker/seed-catalog.sql) and Pricing Engine parameters
# for every subcategory that has none. Safe to re-run: it never overwrites data
# an admin has changed. Self-contained so Railway can build it with this folder
# as the service root:   docker build -t ji-hajoor-seed deploy/seed
# seed-catalog.sql is a copy of docker/seed-catalog.sql (CI checks they match).
FROM postgres:16-alpine
RUN apk add --no-cache curl jq
COPY seed-catalog.sql /seed/seed-catalog.sql
COPY seed.sh test-data.sh /seed/
RUN chmod 755 /seed/seed.sh /seed/test-data.sh
ENTRYPOINT ["/seed/seed.sh"]
