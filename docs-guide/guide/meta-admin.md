# Meta endpoints and admin panel

`MetaEndpoints` mounts a set of developer and operations tools generated from your server definition: OpenAPI and
Swagger UI, live SDK downloads, a health check, a model admin panel, a route list, a WebSocket tester and the bulk
request endpoint.  Mount it once; it needs no other code.

<!-- sample: com/lightningkite/lightningserver/guide/samples/MetaAdminSamples.kt#meta-imports -->
```kotlin
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.MetaEndpoints
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Database
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/MetaAdminSamples.kt#meta-server -->
```kotlin
object MetaServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())

    val meta = path.path("meta") module MetaEndpoints(
        packageName = "com.example.myapp",
        cache = cache,
    )

    init {
        registerBasicMediaTypeCoders()
    }
}
```

`packageName` is the Kotlin package of the downloadable SDK.  `cache` stores health-check results.  Open
`/meta/` in a browser for a page linking to everything below.

## What's mounted

Paths are relative to where you mount it (`/meta` here).

| Path | Property | What it does |
|---|---|---|
| `GET /meta/` | `root` | HTML index of the tools |
| `GET /meta/online` | `isOnline` | `200` with `Server is running.`: a liveness probe that touches no services |
| `GET /meta/health` | `health` | `ServerHealth` JSON: version, memory, CPU load and every service's health check |
| `GET /meta/openapi` | `openApi` | Swagger UI when the client accepts `text/html`, otherwise the OpenAPI JSON |
| `GET /meta/openapi.json` | `openApiJson` | The OpenAPI spec, always JSON |
| `GET /meta/kschema` | `kschema` | Lightning Server's own schema, richer than OpenAPI, used by SDK generators |
| `GET /meta/docs/` | `docs.index` | HTML reference of every typed endpoint and type, with SDK download links |
| `GET /meta/docs/sdk.ts`, `sdk.ts.zip` | | TypeScript SDK, one file or zipped (`?comments=false`, `?erasable=true`) |
| `GET /meta/docs/sdk.kt`, `sdk.kt.zip` | | Kotlin SDK, one file or zipped |
| `GET /meta/admin/` | `admin` | The admin panel (`admin2` is the same; `admin-beta` serves the beta build) |
| `GET /meta/paths` | `paths` | Every HTTP route, WebSocket, schedule and task |
| `GET /meta/ws-tester` | `wsTester` | A page for connecting to a WebSocket and sending messages |
| `POST /meta/bulk` | `bulk` | Several typed calls in one request; see [Bulk endpoints](bulk-endpoints.md) |

The SDK downloads are generated from the running definition, so they always match it.  For build-time generation
see [Client SDKs](client-sdks.md).

## Health checks

`/meta/health` calls `healthCheck()` on every setting that resolves to a service (database, cache, files, email,
and so on), keyed by setting name:

```json
{
  "serverId": "...",
  "version": "1.4.0",
  "memory": { "max": 4294900000, "total": 268400000, "free": 120000000, "systemAllocated": 148400000, "usage": 0.0345 },
  "features": {
    "database": { "level": "OK", "checkedAt": "2026-11-17T12:00:00Z" },
    "cache": { "level": "OK", "checkedAt": "2026-11-17T12:00:00Z" }
  },
  "loadAverageCpu": 0.12
}
```

Each result is cached for the service's `healthCheckFrequency` (one minute by default; failures at most 10 seconds),
so polling is cheap.  A check that takes longer than 10 seconds reports `ERROR`.  The endpoint always returns 200;
read `level` (`OK`, `WARNING`, `URGENT`, `ERROR`) to decide.  Some checks write: the file system's creates and deletes
a file under `health-check/`.

Point load balancers at `/meta/online`, not `/meta/health`: a slow dependency shouldn't take instances out of
rotation.

## Admin panel

`/meta/admin/` serves a KiteUI app that manages every model exposed through
[`ModelRestEndpoints`](model-rest.md): list, filter, sort, create, edit and delete, using the same `Condition`
and `Modification` API as the endpoints.  It is a client of your API like any other, so model permissions decide
what it can see and change.

The page is fetched on each request from `https://ls5admin.cs.lightningkite.com` and has your public URL injected.
The server therefore needs outbound HTTPS to that host.  To serve another build, include your own
`MetaEndpoints.LsKuiAdminModule(deployment = "https://...")`.

## Restricting access

Every meta endpoint uses `noAuth`.  The OpenAPI spec, kschema, docs and SDKs describe your whole API; health reveals
which services you run and their state.  In production, block `/meta` at the load balancer or reverse proxy (or
mount it only in non-production builds) and expose `/meta/online` alone if you need it.

## Next

- [Client SDKs](client-sdks.md): generating SDKs at build time.
- [Bulk endpoints](bulk-endpoints.md): `/meta/bulk` in detail.
- [Observability](observability.md): traces and metrics.
- [Model REST endpoints](model-rest.md): what the admin panel manages.
