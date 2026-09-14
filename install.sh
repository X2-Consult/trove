#!/usr/bin/env bash
#
# Native (non-Docker) local install for Trove.
#
# Sets up everything needed to run the backend and frontend directly on this
# machine, backed by a local PostgreSQL database, managed as systemd
# services. Supports two modes (asked interactively, or set INSTALL_MODE):
#   - production: builds the Angular app and embeds it in a compiled backend
#     jar (java -jar, single process/port). Recommended for a real server.
#   - dev: hot reload via gradlew bootRun + ng serve, two processes. What
#     this script originally did, kept for local development boxes.
# Once installed, the mode is remembered (stored in the credentials file) so
# re-running this script or deploy.sh won't silently flip it.
#
# Relocates the checkout to /opt/trove (standard FHS location for
# third-party app code) if not already there. Optionally configures a
# reverse proxy (Caddy or nginx+certbot, whichever is present) with a Let's
# Encrypt certificate if you're hosting this publicly. Safe to re-run.
#
# Prerequisites: Ubuntu/Debian-like system with apt and systemd. Needs sudo
# for: relocating to /opt, installing PostgreSQL/nginx/caddy/certbot if
# missing, creating the trove Postgres role/database, writing the
# credentials file, and installing the systemd units + reverse proxy config.
# You'll be prompted for your sudo password interactively where needed.
#
# Usage:
#   ./install.sh
#   INSTALL_MODE=production ./install.sh    # skip the mode prompt
#   INSTALL_DIR=/custom/path ./install.sh   # override the /opt/trove default
#
# A machine that already runs Booklore (/etc/booklore/booklore.env) is moved over with
# scripts/migrate-to-trove.sh instead; this script refuses to set up a second, empty install.
#   CADDYFILE=/path/to/Caddyfile ./install.sh  # override Caddyfile discovery
#

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="${INSTALL_DIR:-/opt/trove}"
APP_USER="$(whoami)"
APP_HOME="$HOME"
DATA_DIR="${DATA_DIR:-/srv/trove}"
LEGACY_DATA_DIR="$APP_HOME/booklore-data"
ENV_FILE="/etc/trove/trove.env"
BOOKLORE_ENV_FILE="/etc/booklore/booklore.env"
DB_NAME="trove"
DB_USER="trove"
API_SERVICE="trove"
UI_SERVICE="trove-ui"
SDKMAN_DIR="$APP_HOME/.sdkman"
JAVA_VERSION="25-tem"
BACKEND_PORT=6060
FRONTEND_PORT=4200

log()  { echo ">> $*"; }
warn() { echo "!! $*" >&2; }

# ─── 0. Existing Booklore install? Migrate it instead ───────────────────────
if [ ! -f "$ENV_FILE" ] && { [ -f "$BOOKLORE_ENV_FILE" ] || [ -f /etc/systemd/system/booklore-api.service ]; }; then
  warn "This machine already runs Booklore ($BOOKLORE_ENV_FILE)."
  warn "Move it to Trove with scripts/migrate-to-trove.sh, which keeps your library, users and settings."
  warn "Until then, ./deploy.sh keeps updating it as it is."
  exit 1
fi

# ─── 0.5 Relocate to /opt/trove (idempotent) ───────────────────────────────
if [ "$REPO_DIR" != "$INSTALL_DIR" ]; then
  if [ -e "$INSTALL_DIR" ]; then
    warn "$INSTALL_DIR already exists and this checkout is at $REPO_DIR - refusing to overwrite."
    warn "Set INSTALL_DIR to override, or remove/relocate the existing $INSTALL_DIR first."
    exit 1
  fi
  log "Moving checkout from $REPO_DIR to $INSTALL_DIR..."
  sudo mkdir -p "$(dirname "$INSTALL_DIR")"
  sudo mv "$REPO_DIR" "$INSTALL_DIR"
  sudo chown -R "$APP_USER":"$APP_USER" "$INSTALL_DIR"
  REPO_DIR="$INSTALL_DIR"
  log "Now at $REPO_DIR - run ./install.sh and ./deploy.sh from there from now on."
fi

# ─── 1. Java 25 ────────────────────────────────────────────────────────────
log "Checking Java..."
JAVA_HOME_RESOLVED=""
if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q '"25'; then
  log "Java 25 already on PATH, skipping install."
  JAVA_HOME_RESOLVED="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
else
  if [ ! -d "$SDKMAN_DIR" ]; then
    log "Installing SDKMAN..."
    curl -s "https://get.sdkman.io" | bash
  fi
  # SDKMAN's own init script isn't set -u clean (references unset vars
  # internally), so relax nounset just around it.
  set +u
  # shellcheck disable=SC1090,SC1091
  source "$SDKMAN_DIR/bin/sdkman-init.sh"
  if [ ! -d "$SDKMAN_DIR/candidates/java/$JAVA_VERSION" ]; then
    log "Installing Java $JAVA_VERSION via SDKMAN..."
    sdk install java "$JAVA_VERSION" < /dev/null
  fi
  sdk default java "$JAVA_VERSION" < /dev/null
  set -u
  JAVA_HOME_RESOLVED="$SDKMAN_DIR/candidates/java/current"
fi
log "JAVA_HOME will be: $JAVA_HOME_RESOLVED"

# ─── 2. Node ───────────────────────────────────────────────────────────────
log "Checking Node..."
if ! command -v node >/dev/null 2>&1 || [ "$(node -v | sed -E 's/^v([0-9]+).*/\1/')" -lt 20 ]; then
  warn "Node 20+ not found. Install it from https://nodejs.org/ and re-run this script."
  exit 1
fi
log "Node $(node -v) OK."

# ─── 3. PostgreSQL ─────────────────────────────────────────────────────────
log "Checking PostgreSQL..."
if ! command -v psql >/dev/null 2>&1; then
  log "Installing PostgreSQL..."
  sudo apt-get update
  sudo apt-get install -y postgresql
fi
sudo systemctl enable --now postgresql

# ─── 4. Postgres role + database (idempotent) ──────────────────────────────
log "Ensuring Postgres role/database exist..."

EXISTING_MODE=""
if [ -f "$ENV_FILE" ]; then
  # Reuse the existing password, ALLOWED_ORIGINS, and install mode so
  # re-running this script doesn't desync the stored DB credentials, wipe out
  # origins added later (e.g. by the reverse-proxy step below, on a previous
  # run), or silently flip an already-installed box between dev and prod.
  DB_PASSWORD="$(grep -oP '(?<=^DATABASE_PASSWORD=).*' "$ENV_FILE" || true)"
  EXISTING_ORIGINS="$(grep -oP '(?<=^ALLOWED_ORIGINS=).*' "$ENV_FILE" || true)"
  EXISTING_MODE="$(grep -oP '(?<=^INSTALL_MODE=).*' "$ENV_FILE" || true)"
fi
DB_PASSWORD="${DB_PASSWORD:-$(openssl rand -base64 24 | tr -d '=+/')}"
ALLOWED_ORIGINS_VALUE="${EXISTING_ORIGINS:-http://localhost:${FRONTEND_PORT}}"

# ─── 4.5 Install mode (dev vs production) ──────────────────────────────────
if [ -n "$EXISTING_MODE" ]; then
  INSTALL_MODE="$EXISTING_MODE"
  log "Reusing existing install mode: $INSTALL_MODE"
elif [ -n "${INSTALL_MODE:-}" ]; then
  log "Using INSTALL_MODE=$INSTALL_MODE from environment."
else
  echo
  MODE_CHOICE=""
  read -rp "Install mode: [1] Production (compiled build, single process - recommended for a real server) or [2] Development (hot reload, gradlew bootRun + ng serve)? [1]: " MODE_CHOICE || true
  if [ "$MODE_CHOICE" = "2" ]; then
    INSTALL_MODE="dev"
  else
    INSTALL_MODE="production"
  fi
fi
if [ "$INSTALL_MODE" != "dev" ] && [ "$INSTALL_MODE" != "production" ]; then
  warn "Invalid INSTALL_MODE '$INSTALL_MODE', must be 'dev' or 'production'."
  exit 1
fi
log "Install mode: $INSTALL_MODE"

sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='${DB_USER}'" | grep -q 1 \
  || sudo -u postgres psql -c "CREATE ROLE ${DB_USER} LOGIN PASSWORD '${DB_PASSWORD}';"
sudo -u postgres psql -c "ALTER ROLE ${DB_USER} WITH PASSWORD '${DB_PASSWORD}';" >/dev/null
sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" | grep -q 1 \
  || sudo -u postgres psql -c "CREATE DATABASE ${DB_NAME} OWNER ${DB_USER};"

# ─── 5. Credentials file (outside the git tree) ────────────────────────────
log "Writing credentials to $ENV_FILE..."
sudo mkdir -p "$(dirname "$ENV_FILE")"
# application.yaml reads app.version from ${APP_VERSION:development}; native installs
# have no build-time injection, so stamp the git version here (deploy.sh re-stamps it
# on every update).
APP_VERSION_VALUE="$(git -C "$REPO_DIR" describe --tags --always 2>/dev/null || git -C "$REPO_DIR" rev-parse --short HEAD 2>/dev/null || echo development)"
sudo tee "$ENV_FILE" > /dev/null <<EOF
DATABASE_URL=jdbc:postgresql://localhost:5432/${DB_NAME}
DATABASE_USERNAME=${DB_USER}
DATABASE_PASSWORD=${DB_PASSWORD}
ALLOWED_ORIGINS=${ALLOWED_ORIGINS_VALUE}
INSTALL_MODE=${INSTALL_MODE}
APP_VERSION=${APP_VERSION_VALUE}
TROVE_REPO_DIR=${REPO_DIR}
EOF
sudo chown "$APP_USER" "$ENV_FILE"
sudo chmod 600 "$ENV_FILE"

# ─── 6. Data directories ────────────────────────────────────────────────────
if [ -d "$LEGACY_DATA_DIR" ] && [ ! -e "$DATA_DIR" ]; then
  log "Migrating data from $LEGACY_DATA_DIR to $DATA_DIR..."
  sudo mkdir -p "$(dirname "$DATA_DIR")"
  sudo mv "$LEGACY_DATA_DIR" "$DATA_DIR"
fi
log "Creating data directories under $DATA_DIR..."
sudo mkdir -p "$DATA_DIR/config" "$DATA_DIR/library" "$DATA_DIR/bookdrop"
sudo chown -R "$APP_USER":"$APP_USER" "$DATA_DIR"

# ─── 7. Frontend dependencies ───────────────────────────────────────────────
log "Running npm install in booklore-ui..."
(cd "$REPO_DIR/booklore-ui" && npm install)

BACKEND_JAR=""
if [ "$INSTALL_MODE" = "production" ]; then
  log "Building Angular app for production..."
  (cd "$REPO_DIR/booklore-ui" && npx ng build --configuration production)

  log "Building backend jar (embeds the Angular build)..."
  # Built in build/next and renamed into build/libs: the running server loads classes from its
  # jar, so writing over it in place breaks the server until the restart (every request hangs),
  # while a rename leaves it reading the old file.
  (cd "$REPO_DIR/booklore-api" && rm -rf build/next && ./gradlew bootJar -x test -PbootJarDir=build/next \
    && mkdir -p build/libs && for jar in build/next/*.jar; do mv -f "$jar" build/libs/; done)
  BACKEND_JAR="$(ls -t "$REPO_DIR"/booklore-api/build/libs/booklore-api-*.jar 2>/dev/null | grep -v -- '-plain.jar' | head -1)"
  if [ -z "$BACKEND_JAR" ]; then
    warn "bootJar didn't produce an executable jar under booklore-api/build/libs/ - aborting."
    exit 1
  fi
  log "Backend jar: $BACKEND_JAR"
fi

# ─── 8. systemd services ────────────────────────────────────────────────────
log "Installing systemd services..."

if [ "$INSTALL_MODE" = "production" ]; then
  sudo tee "/etc/systemd/system/${API_SERVICE}.service" > /dev/null <<EOF
[Unit]
Description=Trove (production, single jar - embeds the frontend)
After=network.target postgresql.service

[Service]
Type=simple
User=${APP_USER}
WorkingDirectory=${REPO_DIR}/booklore-api
EnvironmentFile=${ENV_FILE}
Environment=JAVA_HOME=${JAVA_HOME_RESOLVED}
Environment=PATH=${JAVA_HOME_RESOLVED}/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
Environment=APP_PATH_CONFIG=${DATA_DIR}/config
Environment=APP_BOOKDROP_FOLDER=${DATA_DIR}/bookdrop
# Quoted: unquoted, systemd splits the value at spaces and Java only receives the first flag.
Environment="JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=50.0 -XX:+UseShenandoahGC -XX:ShenandoahGCHeuristics=compact -XX:+UseCompactObjectHeaders -XX:InitialRAMPercentage=8.0 -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:CICompilerCount=2 -XX:MaxDirectMemorySize=256m -XX:+UseStringDeduplication -XX:+UnlockExperimentalVMOptions -XX:ShenandoahUncommitDelay=5000 -XX:ShenandoahGuaranteedGCInterval=30000 -XX:+ExitOnOutOfMemoryError"
ExecStart=/bin/bash -c 'exec "${JAVA_HOME_RESOLVED}/bin/java" -jar "\$(ls -t ${REPO_DIR}/booklore-api/build/libs/booklore-api-*.jar | grep -v -- "-plain.jar" | head -1)" --spring.profiles.active=prod'
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

  # No separate frontend service in production mode - it's embedded in the jar.
  if [ -f "/etc/systemd/system/${UI_SERVICE}.service" ]; then
    log "Removing leftover ${UI_SERVICE}.service from a previous dev-mode install..."
    sudo systemctl disable --now "$UI_SERVICE" 2>/dev/null || true
    sudo rm -f "/etc/systemd/system/${UI_SERVICE}.service"
  fi

  sudo systemctl daemon-reload
  sudo systemctl enable --now "$API_SERVICE"
else
  sudo tee "/etc/systemd/system/${API_SERVICE}.service" > /dev/null <<EOF
[Unit]
Description=Trove backend (dev, gradlew bootRun)
After=network.target postgresql.service

[Service]
Type=simple
User=${APP_USER}
WorkingDirectory=${REPO_DIR}/booklore-api
EnvironmentFile=${ENV_FILE}
Environment=JAVA_HOME=${JAVA_HOME_RESOLVED}
Environment=PATH=${JAVA_HOME_RESOLVED}/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
Environment=APP_PATH_CONFIG=${DATA_DIR}/config
Environment=APP_BOOKDROP_FOLDER=${DATA_DIR}/bookdrop
ExecStart=${REPO_DIR}/booklore-api/gradlew bootRun --args=--spring.profiles.active=dev
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

  sudo tee "/etc/systemd/system/${UI_SERVICE}.service" > /dev/null <<EOF
[Unit]
Description=Trove frontend (dev, ng serve)
After=network.target

[Service]
Type=simple
User=${APP_USER}
WorkingDirectory=${REPO_DIR}/booklore-ui
Environment=PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
ExecStart=$(command -v npx) ng serve --host 0.0.0.0
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

  sudo systemctl daemon-reload
  sudo systemctl enable --now "$API_SERVICE" "$UI_SERVICE"
fi

# ─── 8.5 Passwordless restart for the in-app "Update now" button ───────────
# Scoped to exactly the two restarts self-update.sh needs - nothing broader.
SYSTEMCTL_BIN="$(command -v systemctl || echo /usr/bin/systemctl)"
SUDOERS_FILE="/etc/sudoers.d/trove"
SUDOERS_LINE="${APP_USER} ALL=(root) NOPASSWD: ${SYSTEMCTL_BIN} restart ${API_SERVICE}, ${SYSTEMCTL_BIN} restart ${UI_SERVICE}"
log "Installing $SUDOERS_FILE (passwordless service restart for in-app updates)..."
printf '%s\n' "$SUDOERS_LINE" | sudo tee "$SUDOERS_FILE" > /dev/null
sudo chmod 0440 "$SUDOERS_FILE"
if ! sudo visudo -cf "$SUDOERS_FILE"; then
  warn "sudoers validation failed - removing $SUDOERS_FILE. The in-app update button will be unavailable."
  sudo rm -f "$SUDOERS_FILE"
fi

# ─── 9. Reverse proxy + TLS (optional, only if publicly hosted) ────────────
echo
FQDN=""
read -rp "Publicly hosting this? Enter the FQDN to configure a reverse proxy + Let's Encrypt (leave blank to skip): " FQDN || true

if [ -n "$FQDN" ]; then
  log "Configuring reverse proxy for $FQDN..."

  # Production mode serves API + WebSocket + the embedded frontend all from
  # one process/port; dev mode still splits frontend traffic to ng serve.
  if [ "$INSTALL_MODE" = "production" ]; then
    SITE_TARGET_PORT="$BACKEND_PORT"
  else
    SITE_TARGET_PORT="$FRONTEND_PORT"
  fi

  HAS_CADDY=false
  HAS_NGINX=false
  # Check the running service, not just PATH - Caddy in particular is often run
  # as a hand-placed binary via a custom systemd unit rather than an apt package.
  { command -v caddy >/dev/null 2>&1 || systemctl is-active --quiet caddy 2>/dev/null; } && HAS_CADDY=true
  { command -v nginx >/dev/null 2>&1 || systemctl is-active --quiet nginx 2>/dev/null; } && HAS_NGINX=true

  # Resolve the actual caddy binary for invocation (validate/reload), since it
  # may not be on PATH even when the service is running.
  CADDY_BIN="$(command -v caddy || true)"
  if [ -z "$CADDY_BIN" ] && $HAS_CADDY; then
    CADDY_BIN="$(systemctl show caddy -p ExecStart --value 2>/dev/null | grep -oP '(?<=path=)\S+' | head -1)"
  fi

  if ! $HAS_CADDY && ! $HAS_NGINX; then
    echo "Neither Caddy nor nginx is installed."
    PROXY_CHOICE=""
    read -rp "Install [1] Caddy (recommended, automatic HTTPS) or [2] nginx+certbot? [1]: " PROXY_CHOICE || true
    if [ "$PROXY_CHOICE" = "2" ]; then
      sudo apt-get update
      sudo apt-get install -y nginx certbot python3-certbot-nginx
      HAS_NGINX=true
    else
      sudo apt-get update
      sudo apt-get install -y debian-keyring debian-archive-keyring apt-transport-https
      curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --yes --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
      curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
      sudo apt-get update
      sudo apt-get install -y caddy
      HAS_CADDY=true
      CADDY_BIN="$(command -v caddy)"
    fi
  fi

  ORIGINS_CHANGED=false
  FQDN_SCHEME="https"

  if $HAS_CADDY; then
    CADDYFILE="${CADDYFILE:-}"
    if [ -z "$CADDYFILE" ]; then
      for candidate in /etc/caddy/Caddyfile "$APP_HOME/Caddyfile"; do
        [ -f "$candidate" ] && CADDYFILE="$candidate" && break
      done
    fi
    if [ -z "$CADDYFILE" ]; then
      CADDYFILE="/etc/caddy/Caddyfile"
      sudo mkdir -p "$(dirname "$CADDYFILE")"
      sudo touch "$CADDYFILE"
    fi
    log "Using Caddyfile: $CADDYFILE"

    if grep -qF "${FQDN} {" "$CADDYFILE" 2>/dev/null; then
      log "Caddyfile already has a block for $FQDN, leaving it alone."
    else
      HTTP_ONLY=""
      read -rp "Serve $FQDN over HTTP only (dev/testing, no cert) or HTTPS? [http/HTTPS]: " HTTP_ONLY || true
      FQDN_SCHEME="https"
      SITE_ADDR="$FQDN"
      TLS_LINE=""
      if [[ "$HTTP_ONLY" =~ ^[Hh] ]]; then
        FQDN_SCHEME="http"
        SITE_ADDR="http://${FQDN}"
        log "Serving $FQDN over plain HTTP (no TLS)."
      else
        PUBLIC_DNS=""
        read -rp "Does $FQDN already have real public DNS pointing at this server? [y/N]: " PUBLIC_DNS || true
        if [[ ! "$PUBLIC_DNS" =~ ^[Yy] ]]; then
          TLS_LINE="	tls internal\n"
          log "No public DNS yet - using Caddy's local CA (self-signed cert)."
          log "Browsers will show a security warning until real DNS + Let's Encrypt are set up."
        fi
      fi

      log "Appending site block to $CADDYFILE..."
      sudo cp "$CADDYFILE" "${CADDYFILE}.bak-$(date +%s)"
      printf '\n%s {\n%b	handle /api/* {\n		reverse_proxy localhost:%s\n	}\n\n	handle /ws* {\n		reverse_proxy localhost:%s\n	}\n\n	handle {\n		reverse_proxy localhost:%s\n	}\n}\n' \
        "$SITE_ADDR" "$TLS_LINE" "$BACKEND_PORT" "$BACKEND_PORT" "$SITE_TARGET_PORT" | sudo tee -a "$CADDYFILE" > /dev/null

      sudo "$CADDY_BIN" validate --config "$CADDYFILE" --adapter caddyfile
      sudo systemctl reload caddy
      if [ "$FQDN_SCHEME" = "http" ]; then
        log "Caddy configured for plain HTTP."
      elif [ -z "$TLS_LINE" ]; then
        log "Caddy configured. It will automatically obtain a Let's Encrypt certificate for $FQDN on first request."
      else
        log "Caddy configured with a locally-trusted self-signed cert for $FQDN."
      fi
      ORIGINS_CHANGED=true
    fi

  elif $HAS_NGINX; then
    NGINX_SITE="/etc/nginx/sites-available/${FQDN}"
    if [ -f "$NGINX_SITE" ]; then
      log "nginx site $NGINX_SITE already exists, leaving it alone."
    else
      log "Writing nginx site config to $NGINX_SITE..."
      sudo tee "$NGINX_SITE" > /dev/null <<EOF
server {
    listen 80;
    server_name ${FQDN};

    # --- Reverse-proxy settings Trove needs (Kobo sync, in-app docs, OIDC) ---
    # Kobo sends very large headers during a sync; the default 4k-8k proxy
    # buffers reject them with "upstream sent too big header" (HTTP 502).
    client_max_body_size 1000M;
    proxy_buffer_size 128k;
    proxy_buffers 4 256k;
    proxy_busy_buffers_size 256k;
    large_client_header_buffers 8 32k;

    # Forwarded headers, set once here and inherited by every location block.
    # Trove builds absolute URLs from these (Kobo download / library-sync
    # links, OIDC redirects). Missing X-Forwarded-Proto -> http:// URLs behind
    # an https site -> redirect loops.
    proxy_set_header Host \$host;
    proxy_set_header X-Real-IP \$remote_addr;
    proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Host \$host;
    proxy_set_header X-Forwarded-Proto \$scheme;
    proxy_set_header X-Forwarded-Port \$server_port;
    # --------------------------------------------------------------------------

    location /api/ {
        proxy_pass http://localhost:${BACKEND_PORT};
    }

    location /ws {
        proxy_pass http://localhost:${BACKEND_PORT};
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        # A location with its own proxy_set_header does NOT inherit the ones
        # above, so the shared headers are repeated here.
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Host \$host;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header X-Forwarded-Port \$server_port;
    }

    location / {
        proxy_pass http://localhost:${SITE_TARGET_PORT};
    }
}
EOF
      sudo ln -sf "$NGINX_SITE" "/etc/nginx/sites-enabled/${FQDN}"
      sudo nginx -t
      sudo systemctl reload nginx

      if ! command -v certbot >/dev/null 2>&1; then
        sudo apt-get update
        sudo apt-get install -y certbot python3-certbot-nginx
      fi
      LE_EMAIL=""
      read -rp "Email for Let's Encrypt renewal notices (leave blank to skip): " LE_EMAIL || true
      if [ -n "$LE_EMAIL" ]; then
        sudo certbot --nginx -d "$FQDN" --agree-tos -m "$LE_EMAIL" --redirect --non-interactive
      else
        sudo certbot --nginx -d "$FQDN" --agree-tos --register-unsafely-without-email --redirect --non-interactive
      fi
      log "nginx + Let's Encrypt configured for $FQDN."
      ORIGINS_CHANGED=true
    fi
  fi

  FQDN_ORIGIN="${FQDN_SCHEME}://${FQDN}"
  if [ "$ORIGINS_CHANGED" = true ] && ! grep -q "$FQDN_ORIGIN" "$ENV_FILE"; then
    CURRENT_ORIGINS="$(grep -oP '(?<=^ALLOWED_ORIGINS=).*' "$ENV_FILE")"
    # sed -i writes a temp file in the same directory before renaming it over
    # the original, which needs write access to /etc/trove itself (root
    # owned), not just the credentials file - hence sudo here.
    sudo sed -i "s#^ALLOWED_ORIGINS=.*#ALLOWED_ORIGINS=${CURRENT_ORIGINS},${FQDN_ORIGIN}#" "$ENV_FILE"
    log "Added ${FQDN_ORIGIN} to ALLOWED_ORIGINS, restarting backend..."
    sudo systemctl restart "$API_SERVICE"
  fi
else
  log "No FQDN given, skipping reverse proxy setup (app stays on localhost only)."
fi

log "Done."
echo
if [ "$INSTALL_MODE" = "production" ]; then
  echo "  App: http://localhost:${BACKEND_PORT}$([ -n "$FQDN" ] && echo "  (or https://$FQDN)")  (frontend + API + WS, one process)"
  echo
  echo "Check status:  systemctl status ${API_SERVICE}"
  echo "Tail logs:     journalctl -u ${API_SERVICE} -f"
else
  echo "  Frontend: http://localhost:${FRONTEND_PORT}$([ -n "$FQDN" ] && echo "  (or https://$FQDN)")"
  echo "  Backend:  http://localhost:${BACKEND_PORT}"
  echo
  echo "Check status:  systemctl status ${API_SERVICE} ${UI_SERVICE}"
  echo "Tail logs:     journalctl -u ${API_SERVICE} -f"
fi
echo "Apply changes: ${REPO_DIR}/deploy.sh"
