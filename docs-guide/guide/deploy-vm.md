# Deploying to a VM

A Lightning Server application is one JVM process that reads a settings file, so it runs on any Linux machine or
container you manage: another cloud, on-premises, or a VPS.  This chapter covers packaging, settings, a systemd
service, a reverse proxy, and deploys.  On AWS, [Deploying to EC2](deploy-ec2.md) automates all of it.

## Package the server

Use Gradle's `application` plugin:

```kotlin
// build.gradle.kts
plugins {
    application
}

application {
    mainClass.set("com.example.MainKt")
    applicationName = "server"
}
```

`./gradlew installDist` produces `build/install/server/` (`bin/server` plus `lib/`); `./gradlew distZip` zips the same
thing.  Copy that directory to the machine, for example to `/opt/my-api/server/`.  Install a Java runtime matching
your build target (Java 17 or newer).

The main function takes a command, so the same build can run pre-deploy tasks or serve:

<!-- sample: com/lightningkite/lightningserver/guide/samples/DeployEc2Samples.kt#main -->
```kotlin
fun main(vararg args: String) {
    val engine = KtorEngine(Server.build())
    engine.settings.loadFromFile(KFile("settings.json"), engine.internalSerializersModule)
    when (args.firstOrNull()) {
        "predeploy" -> engine.runPreDeploy()
        else -> engine.start(Netty)
    }
}
```

`settings.json` is resolved against the working directory.  Engine choice and engine settings are covered in
[Engines](engines.md) and [Running](running.md).

## Settings

Run the server once without a settings file: it writes `settings.json` with every setting at its default and exits.
Edit it to point at real backends, then start again.  If the file exists but lacks settings, the server writes
`settings.suggested.json` with the missing keys filled in and exits; copy them over.

```json
{
  "database": "mongodb+srv://user:password@cluster.example.net/app",
  "cache": "redis://10.0.0.5:6379",
  "files": "s3://my-bucket.s3-us-west-2.amazonaws.com/",
  "ktorRunConfig": { "host": "127.0.0.1", "port": 8080, "realIpHeader": "X-Real-IP" }
}
```

Keys are the names passed to `setting(...)`; [Settings](settings.md) has the full format.

The file holds credentials, so keep it out of the build and readable only by the service user.  To keep it encrypted
on disk, encrypt it with OpenSSL and give the server the password in `LIGHTNING_SERVER_SETTINGS_DECRYPTION`;
`loadFromFile` decrypts in memory:

```bash
openssl enc -aes-256-cbc -pbkdf2 -iter 10000 -md sha256 -in settings.plain.json -out /etc/my-api/settings.json
```

## Run it with systemd

```ini
# /etc/systemd/system/my-api.service
[Unit]
Description=My API
After=network-online.target
Wants=network-online.target

[Service]
User=my-api
WorkingDirectory=/var/lib/my-api
ExecStart=/opt/my-api/server/bin/server serve
Environment=JAVA_OPTS=-Xmx1g
# Holds LIGHTNING_SERVER_SETTINGS_DECRYPTION=..., if the settings are encrypted.
EnvironmentFile=-/etc/my-api/secrets.env
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Put (or link) `settings.json` in `/var/lib/my-api`, then `sudo systemctl daemon-reload && sudo systemctl enable --now my-api`.
Logs go to the journal: `journalctl -u my-api -f`.

On `SIGTERM` (`systemctl stop`, `restart`) the engine stops accepting connections, drains in-flight requests for up
to `reliability.shutdownDrainTimeout` (25 s by default), and disconnects services.  No shutdown code is needed.

## Deploy a new version

1. Copy the new build next to the old one.
2. Run its pre-deploy tasks while the old version still serves:
   `sudo -u my-api sh -c 'cd /var/lib/my-api && /opt/my-api/server-new/bin/server predeploy'`.  Stop if it fails.
3. Swap the directories and `systemctl restart my-api`.
4. Check `GET /meta/online`; if it doesn't answer, swap back and restart.

This is what the EC2 deployment's on-instance scripts automate.

## Reverse proxy

Terminate TLS in nginx (or Caddy, or a cloud load balancer) and keep the app on localhost:

```nginx
server {
    listen 443 ssl;
    http2 on;
    server_name api.example.com;
    ssl_certificate     /etc/letsencrypt/live/api.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.example.com/privkey.pem;
    client_max_body_size 16m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }
}
```

Set the engine's `realIpHeader` to the header the proxy sets (`X-Real-IP` above) so requests carry the client's
address.  The engine uses the header's right-most comma-separated entry, which is the one the proxy wrote, so
`X-Forwarded-For` with nginx's `$proxy_add_x_forwarded_for` works too; never name a header the proxy doesn't set.  `requestIdHeader` works the same way for a request id
the proxy stamps.  Keep `client_max_body_size` in line with the engine's `reliability.maxBodySize` (16 MiB default).

## Containers

The same distribution runs in a container:

```dockerfile
FROM eclipse-temurin:21-jre
COPY build/install/server /opt/server
WORKDIR /data
ENTRYPOINT ["/opt/server/bin/server"]
CMD ["serve"]
```

```bash
docker run -p 8080:8080 -v "$PWD/settings.json:/data/settings.json:ro" my-api
```

Inside a container, bind `0.0.0.0` (the default) rather than `127.0.0.1`.  Run `predeploy` as a one-off container
(`docker run ... my-api predeploy`) before replacing the running one.

## More than one server

Each engine coordinates through two settings, which default to in-memory and must be shared once there is more than
one process:

| Setting key | Used for |
|---|---|
| `engine-cache` | Per-schedule locks, so each scheduled tick runs on one server |
| `engine-pubSub` | Delivering WebSocket messages to connections held by other servers |

Point both at the same Redis or Valkey (`"redis://..."`); DynamoDB and Memcached also work for the cache.  Point load
balancer health checks at `/meta/online` (mount `MetaEndpoints` at `meta`), which only checks that the process is up,
rather than `/meta/health`, which also checks services and would take every server out of rotation when one of them
is slow.

## Next

- [Deploying to EC2](deploy-ec2.md): all of this, generated for AWS.
- [Engines](engines.md): choosing and tuning the engine.
- [Settings](settings.md): settings files and secrets.
- [Schedules](schedules.md): how scheduled tasks coordinate.
