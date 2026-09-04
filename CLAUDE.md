# Animal Wellbeing

Scala 3 (3.5.2) multi-project sbt build. Developed inside a Docker devcontainer
(`.devcontainer/Dockerfile`) with sbt, coursier and node preinstalled — assume commands run
there unless told otherwise.

## Projects

- `common/` — cross-compiled (JS+JVM) model classes shared by client and server, in package
  `fivedomains.model`. Case classes serialize to JSON via upickle; add `derives ReadWriter`
  directly on the case class/enum rather than declaring a `given` elsewhere, so client and
  server always agree on the wire format.
- `client/` — Scala.js front end using **Doctacular/Veautiful** for UI, bundled by **vite**
  (`npm run dev` / `npm run build`), with TypeScript interop via ScalablyTyped.
- `server/` — back end using **Cask** (Li Haoyi's micro-framework, in `fivedomains.server`)
  with plain JDBC + HikariCP against Postgres (`fivedomains.database`). No ORM: SQL is written
  by hand and kept short.
- `server-legacy-zio/` — the previous zio-http + quill-jdbc-zio back end, moved aside and
  **not** wired into `build.sbt`. Reference only; don't build on it.

## Backend conventions

- User and animal *records* get real columns (id, owner, timestamps) for querying/joins.
  Survey/assessment data and the full `Animal`/`Assessment` payloads are stored as `jsonb`
  (see `initdb/init.sql`), serialized with `upickle.default.write`. This lets the shape of
  survey data evolve without a schema migration for every field — prefer this pattern for new
  data rather than adding columns.
- DB connection config comes from env vars (`PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`,
  `PGPASSWORD`, `PGPOOLSIZE`), defaulting to the `docker-compose.yaml` postgres service.
- CORS is handled by `fivedomains.server.Cors`; allowed origin via `CORS_ALLOW_ORIGIN` (default `*`).
- Outbound calls to an AI API should use `requests` (already a dependency) — keep API keys in
  env vars, never committed.

## Running things

- `docker-compose up` — local Postgres (port 25432) + adminer (port 8080).
- `sbt awServer/run` (or `awServer/reStart` via sbt-revolver for reload-on-change) — API on
  port 8081 (override with `PORT`).
- `npm run dev` in `client/` — vite dev server for the front end.
- `sbt awClient/deployFast` / `deployFull` — used by the GitHub Pages deploy workflow.

## Keeping it neat

- This is a research prototype, not a production system — favor the simplest thing that works
  over frameworks/abstractions "in case we need it later". Small, obvious SQL and JDBC over
  query builders/ORMs; plain functions over layers of indirection.
- No secrets in the repo — DB and AI API credentials are env vars only.
- Don't reintroduce a duplicate `given ReadWriter[...]` for a `common` model type in client or
  server code; it belongs on the case class in `common` via `derives ReadWriter`.
