#!/usr/bin/env bash
#
# Apply code changes to the native (non-Docker) local Trove install set up
# by install.sh: pulls latest code, reinstalls frontend deps if needed, and
# restarts the app.
#
# In production mode (single jar, embeds the frontend): rebuilds the Angular
# app and the backend jar, then restarts the trove service only.
# In dev mode: a fresh gradlew bootRun recompiles the backend and lets Flyway
# apply any new migrations against Postgres on boot, so restarting
# trove / trove-ui is enough - no separate build step needed.
#
# A Booklore install that hasn't been through scripts/migrate-to-trove.sh yet is
# still deployed under its old names (/etc/booklore, booklore-api, booklore-ui).
#
# Usage:
#   ./deploy.sh                 # git pull, then restart
#   ./deploy.sh --skip-pull     # restart only, e.g. to deploy uncommitted edits
#   ./deploy.sh --skip-pg-check # skip the pg_stat_statements check (used by the in-app updater)
#

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKIP_PULL=false
SKIP_PG_CHECK=false
for arg in "$@"; do
  case "$arg" in
    --skip-pull) SKIP_PULL=true ;;
    --skip-pg-check) SKIP_PG_CHECK=true ;;  # used by the in-app updater (no postgres sudo)
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

log() { echo ">> $*"; }
warn() { echo ">> WARNING: $*" >&2; }

if [ -f /etc/trove/trove.env ]; then
  ENV_FILE="/etc/trove/trove.env"
  API_SERVICE="trove"
  UI_SERVICE="trove-ui"
elif [ -f /etc/booklore/booklore.env ]; then
  ENV_FILE="/etc/booklore/booklore.env"
  API_SERVICE="booklore-api"
  UI_SERVICE="booklore-ui"
  warn "This is still a Booklore install; scripts/migrate-to-trove.sh moves it to the Trove layout."
else
  echo ">> ERROR: no /etc/trove/trove.env - run ./install.sh first." >&2
  exit 1
fi

# Stamp the running version into the systemd EnvironmentFile as APP_VERSION, which
# application.yaml reads as `app.version` (${APP_VERSION:development}). Native installs
# have no build-time version injection like the Docker/CI image does, so without this
# the app reports "development" forever. `git describe` yields the release tag on a
# tagged master HEAD (e.g. v1.2.3) or "<tag>-<n>-g<sha>" mid-branch.
stamp_app_version() {
  local version tmp
  version="$(git -C "$REPO_DIR" describe --tags --always 2>/dev/null || git -C "$REPO_DIR" rev-parse --short HEAD 2>/dev/null || true)"
  [ -z "$version" ] && { warn "Could not determine a version to stamp; leaving APP_VERSION as-is."; return; }

  # Build the new file content in a temp file: every line except APP_VERSION, then APP_VERSION.
  # (sed -i / tee -a can't touch $ENV_FILE when only the file - not its dir - is writable.)
  tmp="$(mktemp)"
  if [ -r "$ENV_FILE" ]; then
    grep -v '^APP_VERSION=' "$ENV_FILE" > "$tmp" 2>/dev/null || true
  fi
  printf 'APP_VERSION=%s\n' "$version" >> "$tmp"

  log "Stamping APP_VERSION=$version into $ENV_FILE"
  if { [ -w "$ENV_FILE" ] && [ -w "$(dirname "$ENV_FILE")" ]; } || [ "$(id -u)" = 0 ]; then
    cat "$tmp" > "$ENV_FILE" || warn "Could not stamp APP_VERSION into $ENV_FILE."
  else
    # cp into an existing file keeps its owner/mode; deploy.sh is run interactively so sudo may prompt.
    sudo cp "$tmp" "$ENV_FILE" || warn "Could not stamp APP_VERSION into $ENV_FILE (app.version will fall back to 'development')."
  fi
  rm -f "$tmp"
}

# pg_stat_statements is the standard tool for finding slow/expensive queries in Postgres,
# but enabling it needs two steps: adding it to shared_preload_libraries (which only takes
# effect after a Postgres restart) and CREATE EXTENSION in the target database. Idempotent -
# on repeat deploys this is just a cheap SHOW + no-op CREATE EXTENSION IF NOT EXISTS; Postgres
# is only restarted the one time it's actually missing.
ensure_pg_stat_statements() {
  log "Checking pg_stat_statements..."

  local db_url db_name
  db_url="$(grep -oP '(?<=^DATABASE_URL=jdbc:postgresql://).*' "$ENV_FILE" 2>/dev/null || true)"
  db_name="${db_url##*/}"
  db_name="${db_name:-trove}"

  local preloaded
  preloaded="$(sudo -u postgres psql -tAc "SHOW shared_preload_libraries;" 2>/dev/null || true)"

  if ! echo "$preloaded" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | grep -qx "pg_stat_statements"; then
    log "pg_stat_statements not preloaded - adding it and restarting Postgres..."
    local merged
    if [ -z "$preloaded" ]; then
      merged="pg_stat_statements"
    else
      merged="${preloaded}, pg_stat_statements"
    fi
    sudo -u postgres psql -c "ALTER SYSTEM SET shared_preload_libraries = '${merged}';" >/dev/null
    sudo systemctl restart postgresql

    log "Waiting for Postgres to come back up..."
    for _ in $(seq 1 30); do
      sudo -u postgres psql -tAc "SELECT 1" >/dev/null 2>&1 && break
      sleep 1
    done
  fi

  sudo -u postgres psql -d "$db_name" -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;" >/dev/null
  log "pg_stat_statements is enabled on database '${db_name}'."
}

# Resolve JAVA_HOME for the production build step below (gradlew bootJar).
# The interactive shell running this script may not have SDKMAN's env
# sourced (e.g. non-login shells, or it was only ever set up for the
# systemd unit's own Environment= line during install.sh).
resolve_java_home() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    return
  fi
  if command -v java >/dev/null 2>&1; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
    export JAVA_HOME
    return
  fi
  if [ -f "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
    set +u
    # shellcheck disable=SC1090,SC1091
    source "$HOME/.sdkman/bin/sdkman-init.sh"
    set -u
  fi
  if [ -d "$HOME/.sdkman/candidates/java/current" ]; then
    JAVA_HOME="$HOME/.sdkman/candidates/java/current"
    export JAVA_HOME
  fi
  if [ -n "${JAVA_HOME:-}" ]; then
    export PATH="${JAVA_HOME}/bin:${PATH}"
  fi
}
resolve_java_home

cd "$REPO_DIR"

INSTALL_MODE="$(grep -oP '(?<=^INSTALL_MODE=).*' "$ENV_FILE" 2>/dev/null || true)"
INSTALL_MODE="${INSTALL_MODE:-dev}"
log "Install mode: $INSTALL_MODE"

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
if [ "$CURRENT_BRANCH" != "master" ]; then
  warn "This checkout is on '$CURRENT_BRANCH', not 'master'. Production should track 'master' (see promote.sh)."
fi

[ "$SKIP_PG_CHECK" = true ] || ensure_pg_stat_statements

LOCK_CHANGED=false
if [ "$SKIP_PULL" = false ]; then
  log "Pulling latest changes..."
  BEFORE_LOCK="$(git rev-parse HEAD:booklore-ui/package-lock.json 2>/dev/null || true)"
  # A single connection: git pull follows tags reachable from the branch it fetches,
  # which is all git describe needs. (A separate `git fetch --tags` here just meant a
  # second SSH handshake and a second key-passphrase prompt.)
  git pull --ff-only
  AFTER_LOCK="$(git rev-parse HEAD:booklore-ui/package-lock.json 2>/dev/null || true)"
  [ "$BEFORE_LOCK" != "$AFTER_LOCK" ] && LOCK_CHANGED=true
else
  log "Skipping git pull (--skip-pull)."
  git diff --quiet HEAD -- booklore-ui/package-lock.json || LOCK_CHANGED=true
fi

if [ "$LOCK_CHANGED" = true ]; then
  log "Frontend dependencies changed, running npm install..."
  (cd "$REPO_DIR/booklore-ui" && npm install)
else
  log "No frontend dependency changes, skipping npm install."
fi

stamp_app_version

if [ "$INSTALL_MODE" = "production" ]; then
  log "Building Angular app for production..."
  (cd "$REPO_DIR/booklore-ui" && npx ng build --configuration production)

  log "Building backend jar (embeds the Angular build)..."
  # Built in build/next and renamed into build/libs: the running server loads classes from its
  # jar, so writing over it in place breaks the server until the restart (every request hangs),
  # while a rename leaves it reading the old file.
  (cd "$REPO_DIR/booklore-api" && rm -rf build/next && ./gradlew bootJar -x test -PbootJarDir=build/next \
    && mkdir -p build/libs && for jar in build/next/*.jar; do mv -f "$jar" build/libs/; done)

  log "Restarting $API_SERVICE..."
  sudo systemctl restart "$API_SERVICE"
else
  log "Restarting services..."
  sudo systemctl restart "$API_SERVICE" "$UI_SERVICE"
fi

log "Waiting for backend to come up..."
for _ in $(seq 1 30); do
  if curl -fs http://localhost:6060/api/v1/healthcheck > /dev/null 2>&1; then
    log "Backend healthy."
    break
  fi
  sleep 2
done

echo
echo "--- $API_SERVICE (last 20 lines) ---"
journalctl -u "$API_SERVICE" -n 20 --no-pager
if [ "$INSTALL_MODE" != "production" ]; then
  echo
  echo "--- $UI_SERVICE (last 10 lines) ---"
  journalctl -u "$UI_SERVICE" -n 10 --no-pager
fi
echo
if [ "$INSTALL_MODE" = "production" ]; then
  log "Done. App: http://localhost:6060"
else
  log "Done. Frontend: http://localhost:4200  Backend: http://localhost:6060"
fi
