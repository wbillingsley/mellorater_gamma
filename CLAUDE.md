# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Animal Wellbeing

Scala 3 (3.5.2) multi-project sbt build. Developed inside a Docker devcontainer
(`.devcontainer/Dockerfile`) with sbt, coursier and node preinstalled — assume commands run
there unless told otherwise. There is no automated test suite (no test framework in `build.sbt`)
and no linter/formatter config — verify changes by compiling and, for UI work, running the app.

## Projects

- `common/` — cross-compiled (JS+JVM) model classes shared by client and server, in package
  `fivedomains.model`. Case classes serialize to JSON via upickle; add `derives ReadWriter`
  directly on the case class/enum rather than declaring a `given` elsewhere, so client and
  server always agree on the wire format.
- `client/` — Scala.js front end using **Doctacular/Veautiful** for UI, with TypeScript interop
  via ScalablyTyped. Despite having its own `package.json`, the actual vite project lives at the
  **repo root** (`index.html`, `main.js`, `vite.config.js`) — `npm run dev` / `npm run build` are
  run from the repo root, not from `client/`. `vite.config.js` uses `@scala-js/vite-plugin-scalajs`
  pointed at sbt project `awClient`, so vite triggers the Scala.js compile itself.
- `server/` — back end using **Cask** (Li Haoyi's micro-framework, in `fivedomains.server`)
  with plain JDBC + HikariCP against Postgres (`fivedomains.database`). No ORM: SQL is written
  by hand and kept short.
- `server-legacy-zio/` — the previous zio-http + quill-jdbc-zio back end, moved aside and
  **not** wired into `build.sbt`. Reference only; don't build on it.

## Domain model (`common/shared/.../model/`)

The app implements a "Five Domains" animal welfare assessment: a user records `Animal`s and
fills in `Assessment` surveys for them. Key types:

- `Domain` — the welfare categories (Nutrition, Environment, Health, three Interactions
  categories, and overall Mental wellbeing); `Domain.scoredDomains` excludes Mental itself.
- `Question` / `Situation` / `Answer` / `Rating` / `Confidence` — a survey is a set of
  `Question`s (each tagged with a `Domain`), answered with a `Rating`-scale `Answer` plus a
  `Confidence`.
- `Assessment.categoryScore`/`overallScore` compute a domain's score once all its questions are
  answered, using a `heuristic` (average capped near the minimum, so one very bad answer pulls
  the score down) rather than a plain mean — see `Assessment.scala` if changing the scoring.
- `advice.scala` maps low scores back to guidance text shown to the user.

## Client architecture (`client/src/main/scala/fivedomains/`)

- UI is built with Doctacular/Veautiful `DHtmlComponent`s and `PushVariable`s (an observable
  cell that re-renders subscribers on change) rather than a framework like React/Redux — state
  lives directly in these components/objects, no separate store layer beyond `DataStore`/`Auth`.
- `Router` (a `HistoryRouter[AppRoute]`) is the app's single entry point: it renders either
  `AccountGate` (when `Auth.state` isn't `LoggedIn`) or the page for the current `AppRoute`, and
  owns hash-based URL parsing/generation.
- `Auth` / `Auth.scala` — passwordless auth. A device holds a bearer token (localStorage,
  `Authorization: Bearer` header on every API call); a recovery phrase shown once at
  registration can link another device. Mirrors `server/.../database/Auth.scala` server-side.
- `DataStore` — holds the actual `Animal`/`Assessment` data, persisted **only to
  `localStorage`**, not synced to the server. The server already exposes `/api/animals` and
  `/api/assessments` (backed by Postgres, see `Animals.scala`/`Assessments.scala`) and `Auth` is
  wired up to it, but the rest of the client hasn't been migrated to call those endpoints yet —
  don't assume animal/assessment data round-trips through the server without checking `DataStore`
  first.

## Backend conventions

- User and animal *records* get real columns (id, owner, timestamps) for querying/joins.
  Survey/assessment data and the full `Animal`/`Assessment` payloads are stored as `jsonb`
  (see `initdb/init.sql`), serialized with `upickle.default.write`. This lets the shape of
  survey data evolve without a schema migration for every field — prefer this pattern for new
  data rather than adding columns.
- DB connection config comes from env vars (`PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`,
  `PGPASSWORD`, `PGPOOLSIZE`), defaulting to the `docker-compose.yaml` postgres service.
- CORS is handled by `fivedomains.server.Cors`; allowed origin via `CORS_ALLOW_ORIGIN` (default `*`).
- Every route that reads/writes a user's own data should go through `Main.authed`, which
  resolves the `MellUser` from the `Authorization` header — never trust an owner id supplied by
  the client body/query directly.
- Outbound calls to an AI API should use `requests` (already a dependency) — keep API keys in
  env vars, never committed.

## Running things

- `docker-compose up` — local Postgres (port 25432) + adminer (port 8080).
- `sbt awServer/run` (or `awServer/reStart` via sbt-revolver for reload-on-change) — API on
  port 8081 (override with `PORT`).
- `npm run dev` (from the **repo root**, not `client/`) — vite dev server for the front end;
  `npm run build` produces `dist/` (used by `.github/workflows/deploy.yml` to publish to
  `gh-pages`). `awClient/deployFast`/`deployFull` sbt tasks exist to copy the linked JS out of
  the gitignored `target/` dir, but the current deploy workflow doesn't call them directly.

## Keeping it neat

- This is a research prototype, not a production system — favor the simplest thing that works
  over frameworks/abstractions "in case we need it later". Small, obvious SQL and JDBC over
  query builders/ORMs; plain functions over layers of indirection.
- No secrets in the repo — DB and AI API credentials are env vars only.
- Don't reintroduce a duplicate `given ReadWriter[...]` for a `common` model type in client or
  server code; it belongs on the case class in `common` via `derives ReadWriter`.
