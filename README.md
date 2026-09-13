<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/brand/trove/trove-logo-dark.svg">
    <source media="(prefers-color-scheme: light)" srcset="assets/brand/trove/trove-logo-light.svg">
    <img src="assets/brand/trove/trove-logo-light.svg" alt="Trove" height="96" />
  </picture>
</p>

<p align="center"><strong>Your library. Your server. Your books.</strong></p>

<p align="center">
Trove is a self-hosted app that brings your entire book collection under one roof.<br/>
Organize, read, annotate, sync across devices, and share, all without relying on third-party services.
</p>

---

> [!NOTE]
> **Trove started as a fork of [BookLore](https://github.com/booklore-app/booklore),** which was abandoned by its maintainer.
> It's maintained here independently under its own name. Security and memory fixes are also ported from
> [Grimmory](https://github.com/grimmory-tools/grimmory), another BookLore continuation. See [What's different](#-whats-different-from-booklore) below.

---

## 🔀 What's Different from BookLore

- **PostgreSQL instead of MariaDB.** The database layer, all schema migrations, and every Docker/Podman/Helm example have been
  ported from MariaDB to PostgreSQL.
- **Native (non-Docker) install & deploy tooling.** `install.sh` sets up a full native install (Java, Node, PostgreSQL,
  systemd services, reverse proxy + TLS via Caddy or nginx/certbot) without requiring Docker. `deploy.sh` pulls and applies
  code changes, and admins can update from inside the app.
- **Metadata that survives bot checks.** Amazon and GoodReads pages are loaded in a headless browser on native installs,
  with request spacing and cool-downs, so bulk metadata fetches keep working.
- **Security hardening.** Books opened in the reader can't reach your login, login tokens are harder to misuse,
  cover downloads can't be pointed at your own network, and several access-control gaps are closed.
- **Lower memory use.** JVM settings that hand unused memory back to the system, plus fixes for leaks during scans and bulk jobs.
- **Help built into the app.** The original docs site went offline with the abandoned project. Trove's help lives at
  `/docs` in the app itself, rewritten for Trove with current screenshots, so in-app help links keep working.

---

## ✨ Features

| | Feature | Description |
|:---:|:---|:---|
| 📚 | **Smart Shelves** | Custom and dynamic shelves that organize themselves with rule-based Magic Shelves, filters, and full-text search |
| 🔍 | **Automatic Metadata** | Covers, descriptions, series, and ratings pulled from Google Books, Open Library, Amazon, GoodReads and more, all editable |
| 📖 | **Built-in Reader** | Open PDFs, EPUBs, comics and audiobooks right in the browser with annotations, highlights, and reading progress |
| 🔄 | **Device Sync** | Connect your Kobo, use any OPDS-compatible app, or sync progress with KOReader. Your library follows you everywhere |
| 👥 | **Multi-User Ready** | Individual shelves, progress, and preferences per user with local or OIDC authentication |
| 📥 | **BookDrop** | Drop files into a watched folder and Trove detects, enriches, and queues them for import automatically |
| 📧 | **One-Click Sharing** | Send any book to a Kindle, an email address, or a friend instantly |

---

## 🚀 Quick Start (Docker)

All you need is [Docker](https://docs.docker.com/get-docker/) and [Docker Compose](https://docs.docker.com/compose/install/).

<details>
<summary><strong>📦 Image Repositories</strong></summary>

| Registry | Image |
|----------|-------|
| GitHub Container Registry | `ghcr.io/x2-consult/trove` |

</details>

### Step 1: Environment Configuration

Create a `.env` file:

```ini
# Application
APP_USER_ID=1000
APP_GROUP_ID=1000
TZ=Etc/UTC

# Database
DATABASE_URL=jdbc:postgresql://postgres:5432/trove
DB_USER=trove
DB_PASSWORD=ChangeMe_TroveApp_2026!

# Storage: LOCAL (default), or NETWORK to stop Trove ever modifying book files (see Network Storage below)
DISK_TYPE=LOCAL

# PostgreSQL
POSTGRES_DB=trove
```

### Step 2: Docker Compose

Create a `docker-compose.yml`:

```yaml
services:
  trove:
    image: ghcr.io/x2-consult/trove:latest
    container_name: trove
    environment:
      - USER_ID=${APP_USER_ID}
      - GROUP_ID=${APP_GROUP_ID}
      - TZ=${TZ}
      - DATABASE_URL=${DATABASE_URL}
      - DATABASE_USERNAME=${DB_USER}
      - DATABASE_PASSWORD=${DB_PASSWORD}
      - DISK_TYPE=${DISK_TYPE}
    depends_on:
      postgres:
        condition: service_healthy
    ports:
      - "6060:6060"
    volumes:
      - ./data:/app/data
      - ./books:/books
      - ./bookdrop:/bookdrop
    healthcheck:
      test: wget -q -O - http://localhost:6060/api/v1/healthcheck
      interval: 60s
      retries: 5
      start_period: 60s
      timeout: 10s
    restart: unless-stopped

  postgres:
    image: postgres:16-alpine
    container_name: postgres
    environment:
      - TZ=${TZ}
      - POSTGRES_DB=${POSTGRES_DB}
      - POSTGRES_USER=${DB_USER}
      - POSTGRES_PASSWORD=${DB_PASSWORD}
    volumes:
      - ./postgres/data:/var/lib/postgresql/data
    restart: unless-stopped
    healthcheck:
      test: [ "CMD", "pg_isready", "-U", "${DB_USER}", "-d", "${POSTGRES_DB}" ]
      interval: 5s
      timeout: 5s
      retries: 10
```

### Step 3: Launch

```bash
docker compose up -d
```

Open **http://localhost:6060**, create your admin account, and start building your library.

More examples: [Podman Quadlets](example-podman/), [Helm chart](example-chart/).

---

## 🖥️ Native Install (no Docker)

On an Ubuntu or Debian server with systemd:

```bash
git clone https://github.com/X2-Consult/trove.git
cd trove
./install.sh
```

The installer sets up Java 25, PostgreSQL, a `trove` database, the `trove` systemd service and, optionally, a reverse
proxy with a Let's Encrypt certificate. The app lives in `/opt/trove`, its data in `/srv/trove`, and its settings in
`/etc/trove/trove.env`. To update later, run `./deploy.sh` from `/opt/trove`, or use **Update now** in the app.

---

## 🔁 Moving from BookLore

- **Native installs** (set up with BookLore's `install.sh`): run `scripts/migrate-to-trove.sh --dry-run` to see what will change,
  then `scripts/migrate-to-trove.sh`. It moves the checkout, data, settings, database and service over to the Trove names,
  keeps your library, users and reading progress, and can roll everything back. Until you migrate, `deploy.sh` keeps updating
  the install under its old names.
- **Docker**: switch the image to `ghcr.io/x2-consult/trove` and keep your existing volumes and database settings. If your
  compose file never set `DATABASE_URL` or `DATABASE_USERNAME`, add `DATABASE_NAME=booklore` and `DATABASE_USERNAME=booklore`,
  because the defaults are now `trove`.
- Kobo and KOReader devices keep syncing without re-pairing, and existing logins stay valid.
- `BOOKLORE_*` environment variables still work; their `TROVE_*` names take precedence.

---

## 🗄️ Network Storage (NAS / NFS / SMB)

Trove works with libraries on a NAS or other network share. Each library folder is checked when it's
registered: if it's on NFS, SMB/CIFS or a similar network filesystem, the library settings show a
**Network share** badge next to it, and Trove adjusts how it treats that folder.

- **Books are never written in place.** When Trove writes metadata into a book, imports from BookDrop,
  or organises files, it builds the new file locally, checks it's a sound EPUB, PDF, comic or audiobook,
  copies it onto the share, reads it back to verify it byte for byte, and only then swaps it in. If the
  connection drops part way, the original stays as it was, and the copy is retried.
- **New books are found by checking the folder**, every 60 seconds by default (`NETWORK_POLL_SECONDS`),
  because shares don't announce files added from other machines. A share that goes offline is left
  alone rather than treated as deleted.
- **File names stay portable.** Characters that SMB and Windows reject (`: ? * " < > |`), trailing spaces
  and reserved names such as `CON` are avoided, and renames that only change upper or lower case work on
  case-insensitive shares.

This has been tested against a real SMB share, including cutting the connection in the middle of a
large copy. NFS support uses the same code but hasn't been tested on real hardware yet.

If you'd rather Trove never modifies your files at all, set `DISK_TYPE=NETWORK`. Trove then keeps
metadata in its database only and turns off writing to files, renaming and reorganising.

---

## 📥 BookDrop: Zero-Effort Import

Drop book files into a folder. Trove picks them up, pulls metadata, and queues everything for your review.

```mermaid
graph LR
    A[📁 Drop Files] --> B[🔍 Auto-Detect]
    B --> C[📊 Extract Metadata]
    C --> D[✅ Review & Import]
```

| Step | What Happens |
|:---|:---|
| 1. **Watch** | Trove monitors the BookDrop folder around the clock |
| 2. **Detect** | New files are picked up and parsed automatically |
| 3. **Enrich** | Metadata is fetched from your configured providers |
| 4. **Import** | You review, tweak if needed, and add to your library |

Mount the volume in `docker-compose.yml`:

```yaml
volumes:
  - ./bookdrop:/bookdrop
```

---

## 💜 Support Trove

Trove is free, open source, and built with care. Here's how you can give back:

| Action | How |
|:---|:---|
| ⭐ **Star this repo** | It's the simplest way to help others find Trove |
| ☕ **Buy me a coffee** | [Ko-fi](https://ko-fi.com/xspader) — a one-time tip to fuel continued development |
| 📢 **Tell someone** | Share Trove with a friend, a subreddit, or your local book club |

---

<div align="center">

## ⚖️ License

**GNU Affero General Public License v3.0**

Trove is based on BookLore, © 2024–2026 the BookLore authors. Trove changes © 2026 the Trove contributors.

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg?style=for-the-badge)](https://www.gnu.org/licenses/agpl-3.0.html)

</div>
