# FlipBot dashboard

React, TypeScript and Vite frontend for bot configuration, saved offers, analytics and runtime monitoring.

## Development

Use Node.js 22+ and npm. Start the backend on port 8081, then run:

```shell
npm ci
npm run dev
```

Vite proxies `/api` to `http://localhost:8081`. Keep account credentials and local environment overrides outside Git.

## Build and checks

```shell
npm run lint
npm run test:refactor
npm run build
```

The regression suite compares views, request handling and styles against commit `184d99a80e4b8b75e86483e935ec0de206394a23` in this public repository. Shallow clones need to fetch that commit first; no private repository history is needed.

The backend Maven build runs `scripts/build-panel.mjs` to build and bundle the matching frontend into `backend/target/classes/static`. The panel manifest records source and asset hashes, and a matching bundle is reused. Generated assets are not committed.

Feature components and controllers live under `src/features/`; route pages live under `src/pages/`, API clients under `src/api/`, shared helpers under `src/shared/`, and styles under `src/styles/`.
