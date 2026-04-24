# Deploy to VM

Last updated December 2024 (`version-5`)

Lightning Server applications can be deployed to traditional virtual machines or bare metal servers. This guide covers
general VM deployment concepts, with AWS EC2 as a fully automated option.

## AWS EC2 (Recommended)

For AWS deployments, use the `deploy-aws-ec2` module which provides:

- Fully automated Terraform generation
- Auto Scaling with Application Load Balancer
- Distributed scheduled task handling via SQS
- CloudWatch integration for logs and metrics

See **[Deploy to AWS EC2](deploy-aws-ec2.md)** for the complete guide.

## Generic VM Deployment

For other cloud providers or on-premises deployment, follow these general steps:

### 1. Build a Fat JAR

```kotlin
// build.gradle.kts
plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

tasks.shadowJar {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "com.yourapp.Main"
    }
}
```

```bash
./gradlew shadowJar
# Output: build/libs/yourapp-all.jar
```

### 2. Create Your Entry Point

```kotlin
// src/main/kotlin/Main.kt
import com.lightningkite.lightningserver.engine.ktor.KtorEngine
import io.ktor.server.netty.Netty
import java.io.File

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        val server = Server.build()
        val engine = KtorEngine(server)

        // Load settings from environment or file
        val settingsFile = System.getenv("SETTINGS_FILE") ?: "settings.json"
        if (File(settingsFile).exists()) {
            engine.settings.loadFromFile(File(settingsFile))
        }

        // Start HTTP server
        val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
        engine.start(Netty, port = port)
    }
}
```

### 3. Install Java Runtime

Amazon Corretto is recommended for production:

```bash
# Amazon Linux / RHEL / CentOS
sudo dnf install -y java-17-amazon-corretto-headless

# Ubuntu / Debian
wget https://corretto.aws/downloads/latest/amazon-corretto-17-x64-linux-jdk.deb
sudo dpkg -i amazon-corretto-17-x64-linux-jdk.deb

# Verify
java -version
```

### 4. Create a Systemd Service

```ini
# /etc/systemd/system/myapp.service
[Unit]
Description=My Lightning Server App
After=network.target

[Service]
Type=simple
User=appuser
WorkingDirectory=/opt/app
ExecStart=/usr/bin/java -Xmx512m -jar /opt/app/server.jar
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

Environment=SETTINGS_FILE=/opt/app/settings.json
Environment=PORT=8080

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable myapp
sudo systemctl start myapp
```

### 5. Set Up a Reverse Proxy

Use nginx or another reverse proxy for TLS termination:

```nginx
# /etc/nginx/conf.d/myapp.conf
server {
    listen 443 ssl http2;
    server_name api.yourapp.com;

    ssl_certificate /etc/letsencrypt/live/api.yourapp.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.yourapp.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### 6. Generate Settings File

Run the application once to generate a default `settings.json`:

```bash
java -jar server.jar
# First run creates settings.json with defaults
# Edit as needed, then restart
```

## Scheduled Tasks on Single Server

For single-server deployments, scheduled tasks run automatically. No additional configuration is needed.

For multi-server deployments without SQS, you'll need to implement your own coordination (database locks, Redis, etc.)
or designate one server as the scheduler.

## Health Checks

Expose a health endpoint for load balancers:

```kotlin
object Server : ServerBuilder() {
    val meta = path("meta") include MetaEndpoints

    // MetaEndpoints provides:
    // GET /meta/online - returns 200 if healthy
    // GET /meta/health - detailed health info
}
```

## See Also

- [Deploy to AWS EC2](deploy-aws-ec2.md) - Automated EC2 deployment
- [Deploy to AWS Lambda](deploy-aws.md) - Serverless deployment
- [Settings Configuration](settings.md) - Configuring your application
