#!/usr/bin/env bash
# Deploy MDMesh behind an existing reverse proxy and connect it to an existing
# PostgreSQL server. Re-running is safe: .env and existing application data are
# preserved, Liquibase applies migrations, and seed data is only installed once.
set -euo pipefail
cd "$(dirname "$0")"

say()  { printf '\033[1;36m%s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m%s\033[0m\n' "$*"; }
err()  { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
rand() { openssl rand -hex 24; }

. ./install/lib/db.sh

command -v docker >/dev/null || { err "Docker is required."; exit 1; }
docker compose version >/dev/null || { err "Docker Compose v2 is required."; exit 1; }
command -v openssl >/dev/null || { err "openssl is required."; exit 1; }

COMPOSE_FILE_PATH=docker-compose.external.yml

if [ ! -f .env ]; then
  BASE_URL="${BASE_URL:-}"
  DB_HOST="${DB_HOST:-}"
  DB_PORT="${DB_PORT:-5432}"
  DB_NAME="${DB_NAME:-mdmesh}"
  DB_USER="${DB_USER:-mdmesh}"
  DB_PASSWORD="${DB_PASSWORD:-}"
  EXTERNAL_NETWORK="${EXTERNAL_NETWORK:-nginx_network}"

  [ -n "$BASE_URL" ] || read -rp "Public URL (e.g. https://mdm.example.com): " BASE_URL
  [ -n "$DB_HOST" ] || read -rp "PostgreSQL Docker hostname: " DB_HOST
  [ -n "$DB_PASSWORD" ] || read -rsp "PostgreSQL password for ${DB_USER}: " DB_PASSWORD
  [ -n "$DB_PASSWORD" ] && printf '\n'
  [ -n "$BASE_URL" ] || { err "BASE_URL is required."; exit 1; }
  [ -n "$DB_HOST" ] || { err "DB_HOST is required."; exit 1; }
  [ -n "$DB_PASSWORD" ] || { err "DB_PASSWORD is required."; exit 1; }

  HOST=${BASE_URL#*://}; HOST=${HOST%%/*}
  HASH_SECRET=$(rand)
  cat > .env <<EOF
BASE_URL=${BASE_URL}
SITE_ADDRESS=:80
TRUSTED_PROXIES=private_ranges
DB_HOST=${DB_HOST}
DB_PORT=${DB_PORT}
DB_NAME=${DB_NAME}
DB_USER=${DB_USER}
DB_PASSWORD=${DB_PASSWORD}
HASH_SECRET=${HASH_SECRET}
SECURE_ENROLLMENT=0
EXTERNAL_NETWORK=${EXTERNAL_NETWORK}
IMAGE_OWNER=${IMAGE_OWNER:-mdmesh-app}
SERVER_VERSION=${SERVER_VERSION:-0.3.1}
WEB_VERSION=${WEB_VERSION:-0.3.1}
SUPERVISOR_IMAGE=mdmesh-supervisor:external
CURRENT_VERSION=${CURRENT_VERSION:-0.3.1}
GITHUB_REPO=${GITHUB_REPO:-MDMesh-app/MDMesh}
UPDATE_CHANNEL=stable
POLL_INTERVAL_HOURS=6
GITHUB_TOKEN=
AUTO_UPDATE=0
APPLY_SUPPORTED=1
COMPOSE_PROJECT_NAME=mdmesh
COMPOSE_FILE=docker-compose.external.yml
COMPOSE_PROFILES=
SMTP_HOST=
SMTP_PORT=25
SMTP_FROM=mdm@${HOST}
SMTP_USERNAME=
SMTP_PASSWORD=
EOF
  chmod 600 .env
  say "Created .env (mode 0600)."
else
  say "Reusing existing .env."
fi

set -a; . ./.env; set +a
HOST=${BASE_URL#*://}; HOST=${HOST%%/*}
COMPOSE=(docker compose)

docker network inspect "$EXTERNAL_NETWORK" >/dev/null 2>&1 || {
  err "External Docker network '$EXTERNAL_NETWORK' does not exist."; exit 1;
}

# Each database operation uses a disposable PostgreSQL client attached to the
# external network; the host does not need psql installed.
PSQL=("${COMPOSE[@]}" --profile tools run --rm -T db-tools psql)
say "Checking external PostgreSQL connection..."
mdm_psql -c 'SELECT 1' >/dev/null || { err "Cannot connect to ${DB_HOST}:${DB_PORT}/${DB_NAME}."; exit 1; }

say "Preparing MDMesh images..."
if [[ ":${COMPOSE_FILE:-}:" == *":docker-compose.agent.yml:"* ]]; then
  "${COMPOSE[@]}" pull server
  "${COMPOSE[@]}" build caddy
else
  "${COMPOSE[@]}" pull server caddy
fi
"${COMPOSE[@]}" build supervisor
say "Starting MDMesh..."
"${COMPOSE[@]}" up -d server supervisor caddy

say "Waiting for Liquibase initialization..."
BOOTED=0
DB_STATE=unavailable
for _ in $(seq 1 60); do
  # The completion marker lives on a persistent volume and can survive a
  # previous deployment. Require the expected schema to be queryable too, so
  # a stale marker can never make setup race Liquibase on a fresh database.
  if "${COMPOSE[@]}" exec -T server test -f /opt/mdmesh/initialized.txt 2>/dev/null; then
    DB_STATE=$(mdm_db_state)
    if [ "$DB_STATE" != unavailable ]; then BOOTED=1; break; fi
  fi
  sleep 5
done
if [ "$BOOTED" != 1 ]; then
  err "Server did not initialize within five minutes."
  "${COMPOSE[@]}" logs --tail 80 server >&2 || true
  exit 1
fi

case "$DB_STATE" in
  fresh)
    say "Seeding initial settings and admin account..."
    ADMIN_PASSWORD=$(rand)
    RESET_TOKEN=$(openssl rand -hex 16)
    mdm_seed "admin@${HOST}" install/sql/hmdm_init.en.sql "$ADMIN_PASSWORD" "$RESET_TOKEN"
    ;;
  seeded)
    ADMIN_PASSWORD=
    say "Existing MDMesh data found; seed skipped."
    ;;
  inconsistent)
    err "Database has devices but no settings row; refusing destructive seed."; exit 1 ;;
  *)
    err "Could not determine MDMesh database state."; exit 1 ;;
esac

mdm_post_seed install/sql/post_seed.sql

say "MDMesh is ready at ${BASE_URL}"
if [ -n "$ADMIN_PASSWORD" ]; then
  printf 'Login: admin\nTemporary password: %s\n' "$ADMIN_PASSWORD"
  warn "Save this password now; it is not stored in clear text."
fi
printf 'Nginx Proxy Manager upstream: http://mdmesh-web:80\n'
