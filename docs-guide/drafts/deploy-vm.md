> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Deploying to a VM or Docker Container

Every Lightning Server application is a self-contained JVM process.  The same
`ServerBuilder` object you test locally with `ServerBuilder.test()` and run in
development with `KtorEngine` can be packaged into a fat JAR, shipped to any
server or container, and started with a single `java -jar` command.  This page
covers that path: building the artifact, writing a `Dockerfile`, wiring in
`settings.json`, and putting a reverse proxy in front.

> **Illustrative snippets.** The Gradle tasks, Dockerfile, nginx config, and
> systemd unit in this page are deployment-level configuration that cannot be
> exercised in unit tests.  They are verified against the engine source in
> `engine-ktor/src/main/kotlin/`, `engine-netty/src/main/kotlin/`,
> `engine-jdk-server/src/main/kotlin/`, and the demo's `main.kt`.

---

## Choosing an Engine

Before packaging, decide which engine your server will use.  The choice is a
one-line change to `main()` and a one-line change to `build.gradle.kts`:

| Engine | Gradle artifact | `start()` call | Notes |
|---|---|---|---|
| `KtorEngine` | `engine-ktor` | `start(Netty)` or `start(CIO)` | Recommended for most deployments. HTTP/1.1 + HTTP/2 + WebSockets. |
| `NettyEngine` | `engine-netty` | `start()` | High-throughput; native epoll on Linux. |
| `JdkEngine` | `engine-jdk-server` | `start()` | Zero external HTTP library. **No WebSocket support.** |

All three produce the same `settings.json` key structure and support graceful
shutdown via SIGTERM.

---

## Writing a `main()` Function

The entry point pattern is identical for every engine:

```kotlin
// Illustrative — verified against demo/src/main/kotlin/.../main.kt and engine source.
// Required imports for KtorEngine:
//   import com.lightningkite.lightningserver.engine.ktor.KtorEngine
//   import com.lightningkite.lightningserver.settings.loadFromFile
//   import com.lightningkite.services.kfile.KFile
//   import io.ktor.server.netty.Netty

fun main() {
    val built = Server.build()
    KtorEngine(built).apply {
        // loadFromFile reads settings.json at the given path.
        // On first run the file does not exist: Lightning Server writes a generated
        // settings.json with defaults and exits.  On second run it loads normally.
        // internalSerializersModule supplies the serializers for custom setting types.
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        // start(Netty) blocks until SIGTERM or SIGINT.  Ktor engine factory choices:
        // Netty (recommended), CIO, Jetty.
        start(Netty)
    }
}
```

For `NettyEngine` or `JdkEngine`, `start()` takes no argument:

```kotlin
// Illustrative.
fun mainNetty() {
    val built = Server.build()
    NettyEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start()
    }
}
```

`KFile` is a thin cross-platform path wrapper from the `service-abstractions`
library.  Pass the path to `settings.json` as a relative or absolute path
string.

---

## Building a Runnable Artifact

### Fat JAR (Shadow Plugin)

The simplest packaging is a fat (shadow) JAR: a single file with all classes
and dependencies merged.

```kotlin
// build.gradle.kts
plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

tasks.shadowJar {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "com.example.api.MainKt"  // your main() file
    }
}
```

```bash
./gradlew :your-module:shadowJar
# Output: build/libs/your-module-all.jar
```

Run it locally first:

```bash
java -jar build/libs/your-module-all.jar
# First run: writes settings.json and exits.
# Edit settings.json, then re-run to start the server.
```

### Application Distribution (Zip)

Gradle's `application` plugin produces a zip with a `bin/` launcher script and
a `lib/` directory — slightly cleaner than a fat JAR for Docker because layers
cache better:

```kotlin
// build.gradle.kts
application {
    mainClass.set("com.example.api.MainKt")
}
```

```bash
./gradlew :your-module:distZip
# Output: build/distributions/your-module.zip
```

The EC2 deployment path (`deploy-aws-ec2`) uses this format; the Lambda
path (`engine-aws-serverless`) uses its own `lambda` Sync task.  For
Docker/VM the fat JAR is simpler.

---

## Dockerfile

```dockerfile
# Illustrative — adjust Java version and paths to match your project.

FROM eclipse-temurin:17-jre-jammy AS runtime

WORKDIR /app

# Copy the fat JAR built by shadowJar.
COPY build/libs/your-module-all.jar server.jar

# settings.json is provided at runtime — do NOT bake it into the image.
# The file path can be overridden via the SETTINGS_FILE env var if you
# extend main() to honour it, or you can mount it as a volume.
VOLUME ["/app/settings"]

EXPOSE 8080

ENTRYPOINT ["java", "-Xmx512m", "-jar", "server.jar"]
```

Build and run:

```bash
docker build -t my-api .
# Mount a directory containing settings.json, or pass SETTINGS_FILE.
docker run -p 8080:8080 -v "$(pwd)/config:/app/settings" my-api
```

If `settings.json` is at `/app/settings/settings.json` and your `main()` reads
`KFile("settings.json")`, adjust the working directory in the container or use
an environment variable to redirect the path:

```kotlin
// Illustrative — read settings path from environment.
fun main() {
    val built = Server.build()
    val settingsPath = System.getenv("SETTINGS_FILE") ?: "settings.json"
    KtorEngine(built).apply {
        settings.loadFromFile(KFile(settingsPath), internalSerializersModule)
        start(Netty)
    }
}
```

---

## Settings and Secrets

### The Two-Run Startup

`loadFromFile` on a missing path does not crash silently: Lightning Server
**writes a generated `settings.json`** with every declared setting filled in
with its default value, then throws `MissingSettingFile`, which exits the process.

1. **First run** — file does not exist → generated `settings.json` is written → process exits.
2. **Edit** — replace mock URIs (`"ram"`, `"console"`, `"local"`) with real backends.
3. **Second run** — file exists → settings loaded → server starts normally.

In a container or VM the generated file approach does not work in production —
you want settings to be provided on first start.  Options:

- **Volume-mount or copy** a pre-edited `settings.json` into the container
  before it runs.
- **Sidecar / init container** that writes `settings.json` from Secrets Manager
  or Vault before the main container starts.
- **Environment variable** pointing to a path where your secret manager has
  already dropped the file.

### Settings JSON structure

The file is a flat JSON object.  Keys match the first argument passed to each
`setting()` call in your `ServerBuilder`:

```json
{
  "ktorRunConfig": {
    "host": "0.0.0.0",
    "port": 8080,
    "realIpHeader": "X-Forwarded-For"
  },
  "database": { "url": "mongodb://user:pass@host:27017/mydb" },
  "cache":    { "url": "redis://cache-host:6379" },
  "files":    { "url": "s3://my-bucket?region=us-east-1" }
}
```

When a key is present but incomplete, Lightning Server writes a
`settings.suggested.json` alongside with the full set of keys — copy the
missing lines from there into `settings.json` and restart.

### Encryption at rest

If you store secrets in `settings.json` (connection strings with passwords,
API keys), **do not** bake the file into the image.  The EC2 deployment path
in `deploy-aws-ec2` demonstrates how to encrypt the file with AES-256 at
apply time and decrypt it at boot with a key from SSM Parameter Store.

---

## Engine Configuration

The engine's network settings live in `settings.json` under a per-engine key:

| Engine | JSON key | Settings class |
|---|---|---|
| `KtorEngine` | `"ktorRunConfig"` | `KtorRuntimeSettings` |
| `NettyEngine` | `"nettyRunConfig"` | `NettyRuntimeSettings` |
| `JdkEngine` | `"jdkRunConfig"` | `JdkRuntimeSettings` |

Key fields (all three engines):

- **`host`** — bind address; `"0.0.0.0"` to accept all interfaces (default).
- **`port`** — listen port; default `8080`.
- **`realIpHeader`** — header to read the client IP from when behind a proxy.
  Set to `"X-Forwarded-For"` when using nginx, Caddy, or a cloud load balancer.
- **`reliability.shutdownDrainTimeout`** — seconds to wait for in-flight
  requests to finish before forcing close on SIGTERM (default 30 seconds).
- **`reliability.maxBodySize`** — maximum accepted request body size.

`NettyEngine` also honours `reliability.idleTimeout` and
`reliability.workerThreads`; `JdkEngine` honours `reliability.workerThreads`.
`KtorEngine` ignores both (Ktor manages its own thread pool internally).

---

## Health Checks

Load balancers and container orchestrators (Kubernetes, ECS) require a
lightweight liveness endpoint.  Add `MetaEndpoints` to your server:

```kotlin
// Illustrative — MetaEndpoints is a built-in ServerBuilder group.
object Server : ServerBuilder() {
    val meta = path.path("meta") include MetaEndpoints
    // ...
}
```

`MetaEndpoints` registers:

- `GET /meta/online` — returns `200 OK` with plain-text `"online"` when the
  process is accepting requests.  Use this for load-balancer health checks.
- `GET /meta/health` — returns a JSON report of service health (database
  reachability, cache ping, etc.).  Use this for deeper monitoring.

Point your health probe at `/meta/online`, not `/meta/health`: a slow database
can make `/meta/health` fail without the process being unhealthy.

---

## Reverse Proxy (nginx)

For direct VM deployments, an nginx (or Caddy) reverse proxy in front of the
JVM process handles TLS termination, HTTP-to-HTTPS redirect, and WebSocket
upgrade headers.  A minimal nginx config:

```nginx
# Illustrative — nginx site config
server {
    listen 80;
    server_name api.example.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name api.example.com;

    ssl_certificate     /etc/letsencrypt/live/api.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.example.com/privkey.pem;

    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;

    add_header Strict-Transport-Security "max-age=63072000" always;

    client_max_body_size 10M;  # match reliability.maxBodySize in settings.json

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;

        proxy_set_header Host              $host;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Real-IP         $remote_addr;

        # WebSocket support
        proxy_set_header Upgrade    $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

Set `"realIpHeader": "X-Forwarded-For"` in `settings.json` under the engine's
config key so Lightning Server logs the client's real IP rather than
`127.0.0.1`.

The `deploy-aws-ec2` module uses **Angie** (an nginx fork with a built-in ACME
client) for the same role on single-instance EC2 deployments, so TLS
certificates are issued and renewed automatically without Certbot.

---

## Systemd Unit (non-Docker VM)

If you are running directly on a VM rather than in a container, a systemd unit
keeps the process alive across reboots and crashes:

```ini
# Illustrative — /etc/systemd/system/my-api.service
[Unit]
Description=My API Server
After=network.target
Requires=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/my-api
ExecStart=/usr/bin/java -Xmx512m -jar /opt/my-api/server.jar
Restart=always
RestartSec=5

# Settings file path
Environment=SETTINGS_FILE=/opt/my-api/settings.json

StandardOutput=append:/var/log/my-api/server.log
StandardError=append:/var/log/my-api/server.log

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable my-api
sudo systemctl start my-api
```

The `deploy-aws-ec2` module generates an equivalent systemd unit automatically
as part of the EC2 user-data / golden-AMI bake.

---

## Graceful Shutdown

All three engines register a JVM shutdown hook at `start()`.  Sending SIGTERM
(what systemd and container runtimes send on stop/restart) triggers a graceful
drain: the engine stops accepting new connections, waits for in-flight requests
to complete (bounded by `reliability.shutdownDrainTimeout`), disconnects
services, and exits cleanly.  No explicit shutdown code is needed in `main()`.

---

## What's Next

- **[Deploying to EC2](deploy-ec2.md)** — automated Terraform-managed EC2 with
  Auto Scaling and an Application Load Balancer.
- **[Deploying to AWS Lambda](aws-deployment.md)** — the serverless path with
  `TerraformAwsServerlessBuilder`.
- **[Running Your Server](running.md)** — deeper coverage of engine selection
  and settings loading.
