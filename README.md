# FlipBot — Vinted Automation Platform

A Java/Spring Boot application for monitoring marketplace listings, coordinating browser workers and managing persistent negotiation workflows. The project combines a PostgreSQL-backed API, a React dashboard, a Java Playwright runtime and a mobile companion.

**Status:** active development. The working application is demonstrated below. The public modules include the runtime refactor; Vinted conversation inspection remains in a separately installed private core.

[![CI](https://img.shields.io/github/actions/workflow/status/jedreekPrograms/flipbot-vinted/ci.yml?branch=main&label=CI)](https://github.com/jedreekPrograms/flipbot-vinted/actions/workflows/ci.yml)

## Watch the application

<p align="center">
  <a href="https://www.youtube.com/watch?v=xaNDLMsuKKk">
    <img src="https://img.youtube.com/vi/xaNDLMsuKKk/maxresdefault.jpg" alt="Watch the FlipBot full demo on YouTube" width="900">
  </a>
</p>

<p align="center">
  <strong>▶ <a href="https://www.youtube.com/watch?v=xaNDLMsuKKk">Watch the full 2:34 FlipBot demo on YouTube</a></strong>
</p>

The recording shows the real application: the dashboard, bot configuration, saved listing history, parallel browser workers and runtime telemetry.

| Approx. time | Walkthrough |
| --- | --- |
| 0:05 | Dashboard and operational metrics |
| 0:25 | Bot configuration and negotiation rules |
| 0:50 | Persisted listing and purchase history |
| 1:15 | Live automation runtime |
| 1:40 | Ten headless browser slots |
| 2:05 | Worker states, cooldowns and errors |
| 2:28 | Dashboard updates alongside runtime logs |

## What the system does

- Discovers listings using model filters or search queries and verifies the target before taking action.
- Persists negotiation steps, timing, conversation identity and outcomes so work can continue after a process restart.
- Coordinates multiple workers while serializing jobs for the same bot.
- Uses database-backed ownership, action guards and audit records to control duplicate real actions.
- Plans daily action quotas alongside the capacity already reserved for active negotiations.
- Records market observations and exposes worker state, retry timing and cooldowns in the dashboard.

## Engineering focus

| Problem | Approach |
| --- | --- |
| Two bots find the same listing | Shared marketplace ownership keyed by marketplace and listing ID |
| A worker fails around a real click | Persistent action guards, confirmation checks and audit information |
| Browser UI or availability changes | Live verification, bounded retries and explicit terminal states |
| An account becomes temporarily blocked | Persisted bot-wide cooldown rather than immediate repeated jobs |
| A process restarts | PostgreSQL holds business state; browser contexts are disposable |

A separate market-statistics observer collects read-oriented data without sharing the normal negotiation queue.

## Technology and modules

| Module | Technology and responsibility |
| --- | --- |
| [Backend](backend/) | Java 21, Spring Boot 4.1, Spring Data JPA, PostgreSQL; application state and APIs |
| [Browser runtime](playwright/) | Java and Playwright; scheduling, discovery and public orchestration |
| [Dashboard](frontend/) | React, TypeScript and Vite; configuration, history and monitoring |
| [Mobile companion](mobile/) | Expo, React Native and WebView; access to the existing dashboard |
| [Local database](docker/) | PostgreSQL 17 through Docker Compose |
| [CI](.github/workflows/ci.yml) | Backend and browser-module tests, frontend checks and mobile type-checking |

Flyway initializes a fresh database and upgrades a recognized existing FlipBot schema. The backend build also bundles the matching dashboard and records its asset hashes, so a packaged backend cannot silently serve an older panel.

## Local development

### Requirements and boundaries

- Java 21; Maven for the Playwright module.
- Node.js 22+ and npm for frontend development.
- Docker with Compose for the local database.
- Browser execution additionally needs Chromium/Playwright and the authorized private core.

The backend and dashboard can be built from a fresh clone. Real negotiation workers additionally require access to the private core. Public tests and browser fixtures run without it; the fixtures use local HTML rather than marketplace accounts.

### Database

```shell
cd docker
docker compose up -d
```

Compose and the backend both default to `flipbot_pr74` on `localhost:5433`. Override `FLIPBOT_DB_HOST`, `FLIPBOT_DB_PORT`, `FLIPBOT_DB_NAME`, `FLIPBOT_DB_USER` and `FLIPBOT_DB_PASSWORD` as needed; Spring datasource overrides are also supported. The checked-in `postgres` credentials are for local development.

On first startup, Flyway applies the fresh schema and migrations, then Hibernate validates it. Existing installations with the recognized FlipBot tables are baselined at version 100 and upgraded through the subsequent migrations. Partial schemas are rejected. Back up an existing database before its first startup with the new version; keep the original encryption key when reusing encrypted account credentials.

### Backend and dashboard

Set `FLIPBOT_ENCRYPTION_KEY` to a Base64-encoded 32-byte key before starting the backend. One way to generate a development key, if Python is installed, is:

```shell
python -c "import os,base64; print(base64.b64encode(os.urandom(32)).decode())"
```

From `backend/`:

```shell
bash ./mvnw compile spring-boot:run
```

On Windows, use `.\mvnw.cmd compile spring-boot:run`. The backend build needs Node/npm and builds the matching frontend automatically. Open the bundled panel at **http://localhost:8081**. `bash ./mvnw verify` also produces an executable JAR containing the verified panel.

For frontend development with hot reload, optionally use a second terminal:

```shell
cd frontend
npm ci
npm run dev
```

The Vite development server proxies `/api` to `http://localhost:8081`.

### Authorized browser runtime

On an authorized Windows machine, from `playwright/`:

```powershell
.\bootstrap-private-core.ps1
```

The script checks out the private `jedreekPrograms/Flipbot-Core` repository into an ignored directory, runs its tests, installs the private Maven artifact and activates the local profile. It uses the machine's configured Git credentials. Reload the Maven project after bootstrapping.

Install the browser matching Playwright 1.63.0 from `playwright/`:

```shell
mvn "-Dexec.mainClass=com.microsoft.playwright.CLI" "-Dexec.args=install chromium" exec:java
```

Run `pl.flipbot.playwright.FlipBotPlaywrightApplication` for scheduled multi-bot execution. The default engine is bundled Chromium; `FLIPBOT_BROWSER_ENGINE=chrome` selects installed Chrome. Without the private core, this entry point deliberately refuses to start.

Sessions are saved under the ignored `playwright/sessions/` directory; `FLIPBOT_SESSION_DIR` can override it. Only one runtime may own a session directory. The public repository contains the orchestration and the bridge API, while conversation DOM/activity inspection stays in the private core.

### Mobile access

See the [mobile guide](mobile/README.md) for the Expo development workflow, standalone APK notes and private Tailscale access to the existing backend.

## Tests

Run these commands from the corresponding module directories:

| Module | Checks |
| --- | --- |
| `backend/` | `bash ./mvnw test`; Windows: `.\mvnw.cmd test` |
| `playwright/` | `mvn test` |
| `frontend/` | `npm ci`, `npm run lint`, `npm run test:refactor`, `npm run build` |
| `mobile/` | `npm install`, `npm run typecheck` |

Backend tests need PostgreSQL and the encryption key. The [CI workflow](.github/workflows/ci.yml) runs with Flyway enabled and Hibernate validation, including fresh-schema and existing-schema upgrade tests. It also checks the actual packaged dashboard and local browser fixtures on Linux and Windows. Private-core tests run in its own repository; its API compatibility test runs here when the private artifact is installed.

The UI regression suite compares against the previous **public** release. If using a shallow clone, fetch its baseline before running it:

```shell
git fetch --no-tags --depth=1 origin 184d99a80e4b8b75e86483e935ec0de206394a23
```

## Runtime configuration

The scheduler accepts environment variables for worker count and scheduling intervals:

```text
FLIPBOT_WORKER_COUNT
FLIPBOT_SYNC_INTERVAL_SECONDS
FLIPBOT_NEGOTIATION_CHECK_INTERVAL_SECONDS
FLIPBOT_CATALOG_SCAN_INTERVAL_SECONDS
FLIPBOT_FAILURE_RETRY_SECONDS
FLIPBOT_RATE_LIMIT_RETRY_SECONDS
FLIPBOT_SHUTDOWN_TIMEOUT_SECONDS
FLIPBOT_SCHEDULER_HEADLESS
```

## Current next steps

- Add a public demonstration mode that does not need private browser integration.
- Extend regression coverage and runtime tracing.
- Improve deployment packaging and the screenshot gallery.

