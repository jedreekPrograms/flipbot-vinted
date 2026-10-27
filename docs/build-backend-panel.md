# Build the backend and dashboard together

Use JDK 21, Node.js 22 or newer, npm, and a checkout that includes the
`backend`, `frontend` and `scripts` directories. Maven Wrapper is included.

From the repository root, in PowerShell:

```powershell
Set-Location backend
.\mvnw.cmd clean verify
java -jar .\target\backend-0.0.1-SNAPSHOT.jar
```

On Linux or macOS:

```sh
cd backend
bash ./mvnw clean verify
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

Start PostgreSQL and set the encryption key as described in the
[local setup guide](../README.md#local-development) before running the tests or
starting the JAR. If you reuse a database with encrypted account credentials,
use the same key as before. The packaged dashboard opens at
<http://localhost:8081>.

## What Maven packages

The Maven `generate-resources` phase runs
[`scripts/build-panel.mjs`](../scripts/build-panel.mjs). The script calculates
a fingerprint from the frontend inputs and the build script. When the existing
bundle and its asset hashes match, it reuses the bundle. Otherwise it installs
locked dependencies with `npm ci`, builds the frontend, and replaces the contents
of `backend/target/classes/static` with the new assets and `panel-build.json`.
A failed build stops Maven. Generated assets stay under `target`.

The backend CI job runs `verify` and checks the executable JAR with
[`scripts/verify_backend_package.py`](../scripts/verify_backend_package.py).
The check compares the manifest and asset hashes and rejects extra or missing
panel files. The `flipbot-backend-with-panel` artifact contains the backend and
dashboard; the database and browser worker run separately.

For a backend run configuration in IntelliJ, run Maven `process-resources`
in the backend module before starting the application. To develop the dashboard
with hot reload instead, use [the frontend setup](../frontend/README.md#development).
