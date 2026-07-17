> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Meta, OpenAPI & Admin Panel

Lightning Server ships a ready-made block of utility endpoints — `MetaEndpoints`
— that you mount once and get a full suite of developer tools for free:

- **Swagger / OpenAPI UI** — browse and try every typed endpoint in a browser
- **Live SDK downloads** — grab a TypeScript or Kotlin SDK without a build step
- **Health check** — a JSON endpoint your load balancer or monitoring can poll
- **Admin panel** — a full CRUD interface for every model you expose via REST
- **WebSocket tester** — a simple browser tool for manual WebSocket inspection
- **Bulk request** — send multiple API calls in a single HTTP round-trip
- **Settings JSON schema** — validate `settings.json` in editors and CI

## Imports

```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.sdk.module
```

## Mounting MetaEndpoints

`MetaEndpoints` is a `ServerBuilder`; mount it with the `module` DSL so it
gets its own SDK namespace.  The constructor requires your package name, a
`Database` setting reference, and a `Cache` setting reference — both are used
by the built-in health check:

```kotlin
// From demo/src/main/kotlin/.../Server.kt (verified against source).
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache    = setting("cache",    Cache.Settings())

    val meta = path.path("meta") module MetaEndpoints(
        packageName = "com.yourcompany.yourproject",
        database    = database,
        cache       = cache,
    )
}
```

All endpoints described below are relative to wherever you mount
`MetaEndpoints`.  The examples below assume the mount path `/meta`.

## Endpoint Reference

### `GET /meta/` — Landing page

Returns an HTML page listing all the meta endpoints as clickable links.  Useful
as an entry point when you first visit the meta section in a browser.

### `GET /meta/online` — Liveness probe

Returns `200 OK` with the plain-text body `"Server is running."`.  Wire this
to your load balancer or Kubernetes liveness probe — it's intentionally trivial
so it can never throw.

### `GET /meta/health` — Health check

Returns a JSON `ServerHealth` object (typed `ApiHttpHandler`, `auth = noAuth`):

```jsonc
{
  "serverId": "my-server",
  "version": "1.0.0",
  "memory": {
    "max": 512000000,
    "total": 256000000,
    "free": 128000000,
    "systemAllocated": 128000000,
    "usage": 0.5
  },
  "loadAverageCpu": 0.12,
  "features": {
    "database": { "level": "OK", "checkedAt": "2026-06-25T00:00:00Z" },
    "cache":    { "level": "OK", "checkedAt": "2026-06-25T00:00:00Z" }
  }
}
```

Each entry in `features` is a `HealthStatus` produced by calling
`healthCheck()` on the service.  Results are cached in the configured cache
service so a slow database doesn't make every health poll expensive.  If a
check times out after 10 seconds it reports `level = ERROR`.

Memory values are rounded to the nearest 100 KB to avoid leaking precision
about JVM internals.

### `GET /meta/openapi` — Swagger UI or OpenAPI JSON

The response format depends on the `Accept` header:

- `Accept: text/html` → serves a self-contained **Swagger UI** page, letting
  you browse and try every typed endpoint directly in a browser.
- Any other `Accept` (or no header) → returns the **OpenAPI 3.0 JSON spec** as
  `application/json`.

This dual behavior means `/meta/openapi` works both as a browser bookmark and
as a machine-readable spec URL for tools like Postman, Insomnia, or your CI
contract-testing pipeline.

### `GET /meta/openapi.json` — OpenAPI JSON (always)

Same as `/meta/openapi` but always returns JSON regardless of `Accept`.
Use this as the stable URL for tooling that explicitly fetches the spec.

### `GET /meta/kschema` — Lightning Server KSchema

Returns Lightning Server's own schema format as JSON.  This is a richer
description than OpenAPI: it includes Lightning Server-specific types like
`Condition`, `Modification`, and path-parameter metadata.  Used internally by
SDK generators and the admin panel; also useful for custom tooling built on top
of the Lightning Server type system.

### `GET /meta/paths` — Full path listing

Returns an HTML page listing every HTTP method + path the server responds to,
plus all registered tasks and scheduled jobs.  Useful for confirming your
routing is correct during development.

### `GET /meta/ws-tester` — WebSocket tester

A browser-based WebSocket testing UI.  Pre-populates the path from a `?path=`
query parameter so you can link directly to a specific WebSocket endpoint.
Reads the `Authorization` cookie for auth if present.

### `POST /meta/bulk` — Bulk requests

Send multiple API calls in a single HTTP request.  The body is a
`Map<String, BulkRequest>` where each key is a caller-defined label and each
value specifies a `path`, `method`, and optional JSON `body`.  The response is
a `Map<String, BulkResponse>` with each call's result (or error) and duration.
Auth, headers, and middleware run normally for each sub-request.

This is useful for mobile clients that want to reduce round-trips at app
startup, or for batch administrative operations.

### `GET /meta/docs` — API documentation + SDK downloads

`ApiDocs` is a sub-module mounted at `/meta/docs`.  Its index page (`/meta/docs/`) is an HTML view of:

- Links to downloadable SDKs
- All typed endpoint summaries with their input/output types and auth requirements
- All types used by those endpoints, expandable with field listings

The SDK download links are live — they generate the SDK from the running server
definition on demand, so they're always up to date:

| URL | Contents |
|---|---|
| `/meta/docs/sdk.ts` | Full TypeScript SDK in a single `.ts` file |
| `/meta/docs/sdk.ts.zip` | TypeScript SDK split into `models.ts`, `Api.ts`, `LiveApi.ts` |
| `/meta/docs/sdk.kt` | Full Kotlin SDK in a single `.kt` file |
| `/meta/docs/sdk.kt.zip` | Kotlin SDK split into interface + live files |

The `?comments=false` query parameter strips JSDoc/KDoc comments from either
SDK download.  The `?erasable=true` parameter switches the TypeScript generator
to emit `type` aliases for enums instead of `enum` declarations (for TypeScript
5.5+ "erasable types" mode).

### `GET /meta/admin` and `GET /meta/admin2` — Admin panel

Both routes serve the same KiteUI-driven admin panel hosted at
`https://ls5admin.cs.lightningkite.com`.  The panel is fetched from that URL and
injected with your server's public URL via a `<script type="application/json">`
tag, so it can connect to your backend.

The admin panel provides:

- **Full CRUD** for every model you've exposed through `ModelRestEndpoints`
- **Endpoint tester** — call any typed endpoint with a form UI
- **Filter and sort** — uses the full `Condition` and `Modification` DSL that
  `ModelRestEndpoints` understands

> The `deployment` URL on `MetaEndpoints.LsKuiAdminModule` defaults to
> `"https://ls5admin.cs.lightningkite.com"`.  To self-host the admin panel or
> point at a different deployment, pass a `LsKuiAdminModule(deployment = "...")` 
> — this is an illustrative note; see `MetaEndpoints.kt` for current constructor
> options.

`/meta/admin-beta` (also present) points at `https://beta.lsadmin.cs.lightningkite.com`
and is the staging channel for admin panel updates.

## Why This Matters

Before `MetaEndpoints` you would have to:

- Write and maintain an OpenAPI spec by hand
- Build or buy an admin panel
- Write health-check endpoints for every service
- Generate client SDKs in a separate build pipeline

With `MetaEndpoints`, all of this is a single line.  Point a developer at
`/meta/openapi` for docs, `/meta/admin` for data management, and `/meta/online`
for monitoring — none of it requires additional code or infrastructure.

The Swagger UI alone eliminates most needs for tools like Postman during
development: every team member can try any endpoint in a browser tab without
installing anything.

## Restricting Access in Production

`MetaEndpoints` uses `auth = noAuth` on most of its endpoints so they work
without configuration.  In production you will typically want to restrict
access.  Options:

1. **Network-level restriction** — put `/meta` behind a VPN or internal load
   balancer route that is not publicly reachable.
2. **Path-level restriction** — do not mount `MetaEndpoints` in production
   builds, or mount only `isOnline` and `health` (both are properties on
   `MetaEndpoints` you can mount individually — illustrative; verify field names
   against `MetaEndpoints.kt` if mounting individually).
3. **Reverse proxy rules** — configure your nginx/ALB to block external access
   to `/meta` while allowing internal traffic.

The health endpoint in particular is safe to expose publicly — it returns
no secrets and is useful for uptime monitoring services.

## What's Next

- **Client SDKs** — for build-time SDK generation and custom `Archive`
  targets, see [Generating & Using Client SDKs](client-sdks.md).
- **Model REST Endpoints** — expose a model through `ModelRestEndpoints` so it
  appears in the admin panel.
- **API contract testing** — use `apiBaselineWrite` and `apiCheck` (from the
  demo's `main.kt`) to diff your API schema in CI and catch breaking changes
  before they ship.
