#!/bin/sh
# Container entrypoint for every backend deployable built by the root Dockerfile.
#
# Translates the variables a PaaS hands out (Railway, Render, Heroku-style) into
# the settings the Spring services read, then starts the jar. Anything already
# set explicitly wins, so the docker-compose / Kubernetes setups are unaffected.
#
#   PORT          -> SERVER_PORT (the gateway; a multi-service app gives each
#                    of its services its own fixed port, which wins over this)
#   DATABASE_URL  -> DB_URL (JDBC form), DB_USERNAME, DB_PASSWORD
#                    postgres[ql]://user:password@host:port/database[?params]
#   REDIS_URL     -> SPRING_DATA_REDIS_URL (carries host, port and password)
set -eu

if [ -n "${PORT:-}" ] && [ -z "${SERVER_PORT:-}" ]; then
  export SERVER_PORT="$PORT"
fi

if [ -n "${DATABASE_URL:-}" ] && [ -z "${DB_URL:-}" ]; then
  rest="${DATABASE_URL#*://}"            # user:password@host:port/db?params
  case "$rest" in
    *@*) creds="${rest%%@*}"; hostpart="${rest#*@}" ;;
    *)   creds="";            hostpart="$rest" ;;
  esac
  export DB_URL="jdbc:postgresql://${hostpart}"
  if [ -n "$creds" ]; then
    case "$creds" in
      *:*) user="${creds%%:*}"; pass="${creds#*:}" ;;
      *)   user="$creds";       pass="" ;;
    esac
    # Railway's credentials are URL-safe, but decode %XX in case a password isn't.
    decode() {
      printf '%s' "$1" | awk '{
        h = "0123456789abcdef"; s = $0; out = ""
        while (match(s, /%[0-9A-Fa-f][0-9A-Fa-f]/)) {
          v = (index(h, tolower(substr(s, RSTART + 1, 1))) - 1) * 16 + index(h, tolower(substr(s, RSTART + 2, 1))) - 1
          out = out substr(s, 1, RSTART - 1) sprintf("%c", v)
          s = substr(s, RSTART + 3)
        }
        printf "%s", out s
      }'
    }
    if [ -z "${DB_USERNAME:-}" ]; then export DB_USERNAME="$(decode "$user")"; fi
    if [ -z "${DB_PASSWORD:-}" ]; then export DB_PASSWORD="$(decode "$pass")"; fi
  fi
fi

if [ -n "${REDIS_URL:-}" ] && [ -z "${SPRING_DATA_REDIS_URL:-}" ]; then
  export SPRING_DATA_REDIS_URL="$REDIS_URL"
fi

# One start-up line to make a misconfigured deploy obvious in the logs (no secrets).
db_target="${DB_URL:-<not set>}"
redis_target="${SPRING_DATA_REDIS_URL:-redis://${REDIS_HOST:-localhost}:${REDIS_PORT:-6379}}"
echo "ji-hajoor: port ${SERVER_PORT:-default}; database ${db_target%%\?*}; redis ${redis_target#*@}"

# Serial GC and small thread stacks suit several low-traffic services sharing one
# JVM; set JAVA_OPTS (e.g. -Xmx900m) to cap the heap below the container size.
exec java -XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k ${JAVA_OPTS:-} -jar /app/app.jar "$@"
