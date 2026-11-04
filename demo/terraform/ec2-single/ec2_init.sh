#!/bin/bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

echo "[INFO] EC2 Init script started on AmazonLinux2023 at $(date)"


# === Disable unattended upgrades ===
echo "[INFO] Disabling dnf automatic update and metadata timers at $(date)"
systemctl disable --now dnf-automatic.timer dnf-automatic-install.timer || true
systemctl disable --now dnf-makecache.timer || true
systemctl mask dnf-makecache.timer || true

# === System update + base packages ===
echo "[INFO] Updating system packages at $(date)"
dnf -y --releasever=latest upgrade
dnf install -y java-17-amazon-corretto-headless openssl unzip
dnf -y autoremove


# === Install CloudWatch Agent ===
echo "[INFO] Installing CloudWatch Agent at $(date)"
dnf install -y amazon-cloudwatch-agent


# === CloudWatch Agent Configuration ===
echo "[INFO] Creating amazon-cloudwatch-agent.json for CloudWatch $(date)"
cat > /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json << EOF
{
  "logs": {
    "logs_collected": {
      "files": {
        "collect_list": [
          {
            "file_path": "/var/log/ec2-demo/ec2_init.log",
            "log_group_name": "ec2-demo-main-log",
            "log_stream_name": "{instance_id}/ec2_init"
          },
          {
            "file_path": "/var/log/ec2-demo/server.log",
            "log_group_name": "ec2-demo-main-log",
            "log_stream_name": "{instance_id}/server"
          },
          {
            "file_path": "/var/log/ec2-demo/redeploy.log",
            "log_group_name": "ec2-demo-main-log",
            "log_stream_name": "{instance_id}/redeploy"
          },
          {
            "file_path": "/var/log/ec2-demo/os-update.log",
            "log_group_name": "ec2-demo-main-log",
            "log_stream_name": "{instance_id}/os-update"
          }
        ]
      }
    }
  },
  "metrics": {
    "metrics_collected": {
      "mem": {
        "measurement": ["mem_used_percent"]
      },
      "disk": {
        "measurement": ["disk_used_percent"],
        "resources": ["/"]
      }
    }
  }
}
EOF

systemctl enable amazon-cloudwatch-agent
systemctl restart amazon-cloudwatch-agent


# === Install nginx + ACME module ===
echo "[INFO] Installing nginx $(date)"
cat > /etc/yum.repos.d/nginx.repo << 'NGINX_REPO_EOF'
[nginx-stable]
name=nginx stable repo
baseurl=https://nginx.org/packages/amzn/2023/$basearch/
gpgcheck=1
enabled=1
gpgkey=https://nginx.org/keys/nginx_signing.key
module_hotfixes=true
priority=9
NGINX_REPO_EOF
dnf install -y nginx nginx-module-acme

# === nginx config (nginx-module-acme handles cert issuance + renewal) ===
echo "[INFO] Creating server.conf for nginx $(date)"
# The ACME module is dynamic, and load_module is only valid at the top level of nginx.conf.
grep -q 'ngx_http_acme_module' /etc/nginx/nginx.conf \
    || sed -i '1i load_module modules/ngx_http_acme_module.so;' /etc/nginx/nginx.conf
rm -f /etc/nginx/conf.d/default.conf
cat > /etc/nginx/conf.d/server.conf << 'NGX_EOF'
resolver 8.8.8.8 1.1.1.1 ipv6=off;
resolver_timeout 5s;

map $http_upgrade $connection_upgrade {
    default   upgrade;
    ''        '';
}

upstream app {
    server 127.0.0.1:8080;
    keepalive 32;
    keepalive_timeout 45s;
    keepalive_requests 1000;
}

acme_issuer letsencrypt {
    uri https://acme-staging-v02.api.letsencrypt.org/directory;
    state_path /var/cache/nginx/acme-letsencrypt;
    accept_terms_of_service;
}

# Drop requests for any other host name or a bare IP
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    listen 443 ssl default_server;
    listen [::]:443 ssl default_server;
    ssl_reject_handshake on;
    return 444;
}

# HTTP: redirect to HTTPS (nginx-module-acme answers ACME challenges before this)
server {
    listen 80;
    listen [::]:80;
    server_name ec2-demo.cs.lightningkite.com;
    return 308 https://$host$request_uri;
}

server {
    server_name ec2-demo.cs.lightningkite.com;
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;

    acme_certificate letsencrypt;
    ssl_certificate     $acme_certificate;
    ssl_certificate_key $acme_certificate_key;
    # Variable certificate paths are otherwise re-read on every handshake.
    ssl_certificate_cache max=2;

    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305;
    ssl_prefer_server_ciphers off;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;
    ssl_session_tickets off;

    add_header Strict-Transport-Security "max-age=63072000" always;
    add_header  X-Robots-Tag "noindex, nofollow, nosnippet, noarchive" always;
    server_tokens off;

    client_max_body_size 10M;

    location / {
        proxy_pass http://app;
        proxy_http_version 1.1;

        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $server_name;
        proxy_set_header X-Real-IP $remote_addr;

        proxy_set_header Upgrade    $http_upgrade;
        proxy_set_header Connection $connection_upgrade;

    }
}


NGX_EOF


# === SSM Agent ===
echo "[INFO] Ensuring SSM agent is installed and running at $(date)"
# Required so terraform can drive in-place jar/settings redeploys via
# `aws ssm send-command`. The AL2023 AMI ships amazon-ssm-agent as an RPM; install if missing,
# then enable + start so newly-created instances register with SSM.
rpm -q amazon-ssm-agent >/dev/null 2>&1 || dnf install -y amazon-ssm-agent
systemctl enable amazon-ssm-agent
systemctl start amazon-ssm-agent


# === Service User ===
echo "[INFO] Creating unprivileged service user lightning-server"
id -u lightning-server >/dev/null 2>&1 || useradd --system --user-group --home-dir /var/lib/ec2-demo --no-create-home --shell /usr/sbin/nologin lightning-server

# === Filesystem Layout ===
# Code: owned by root, so the service can run it but never modify it.
install -d -o root -g root -m 0755 /opt/ec2-demo
# Config (settings.json, Config instance files): readable by the service, writable only by root.
install -d -o root -g lightning-server -m 0750 /etc/ec2-demo
# State and cache: the service's own. systemd's StateDirectory=/CacheDirectory= also manage these.
install -d -o lightning-server -g lightning-server -m 0700 /var/lib/ec2-demo
install -d -o lightning-server -g lightning-server -m 0700 /var/cache/ec2-demo

# === Systemd Service ===
echo "[INFO] Creating Systemd unit for Lightning Server EC2 Demo"
cat > /etc/systemd/system/ec2-demo.service << 'UNIT_EOF'
[Unit]
Description=Lightning Server EC2 Demo
After=network.target
Requires=network.target

[Service]
Type=simple
User=lightning-server
Group=lightning-server
WorkingDirectory=/var/lib/ec2-demo
ExecStart=/opt/ec2-demo/server/bin/server serve
StateDirectory=ec2-demo
StateDirectoryMode=0700
CacheDirectory=ec2-demo
CacheDirectoryMode=0700
LogsDirectory=ec2-demo
# Apps load settings.json relative to the working directory. A read-only bind mount (not a symlink,
# which the service could swap since it owns the directory) keeps that working without letting the
# service replace it. No '-' prefix: with no settings deployed yet, the service must not start.
BindReadOnlyPaths=/etc/ec2-demo/settings.enc:/var/lib/ec2-demo/settings.json
# Settings stay encrypted on disk. '+' runs the key fetch as root outside the sandbox; it writes the
# RAM-backed, root-only file whose password systemd hands to the app. systemd loads EnvironmentFile=
# before every Exec* (ExecStartPre included), so '-' lets the key fetch run before the file exists.
ExecStartPre=+/usr/local/bin/ec2-demo-settings-key
EnvironmentFile=-/run/ec2-demo-secrets/settings.env
NoNewPrivileges=true
UMask=0077
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
PrivateDevices=true
ProtectProc=invisible
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectKernelLogs=true
ProtectControlGroups=true
ProtectClock=true
ProtectHostname=true
RestrictSUIDSGID=true
RestrictNamespaces=true
RestrictRealtime=true
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6 AF_NETLINK
LockPersonality=true
SystemCallArchitectures=native


Restart=always
RestartSec=5
StandardOutput=append:/var/log/ec2-demo/server.log
StandardError=append:/var/log/ec2-demo/server.log




[Install]
WantedBy=multi-user.target
UNIT_EOF

# === Server log file ===
echo "[INFO] Preparing server log directory"
mkdir -p /var/log/ec2-demo
touch /var/log/ec2-demo/server.log
chown -R lightning-server:lightning-server /var/log/ec2-demo

cat > /etc/logrotate.d/ec2-demo << 'LOGROTATE_EOF'
/var/log/ec2-demo/*.log {
    daily
    rotate 7
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
    su lightning-server lightning-server
}
LOGROTATE_EOF


# === Lightning Server Redeploy Script ===
echo "[INFO] Creating Lightning Server Redeploy Script"
cat > /usr/local/bin/lightning-server-redeploy << 'REDEPLOY_EOF'
#!/bin/bash
set -euo pipefail

log() { echo "[lightning-server-redeploy] $(date '+%Y-%m-%d %H:%M:%S') $*"; }
err() { echo "[lightning-server-redeploy] $(date '+%Y-%m-%d %H:%M:%S') ERROR: $*" >&2; }

BUCKET="${deployment_bucket}"
REGION="us-west-2"
SSM_PARAM="/ec2-demo/settings-password"
APP_DIR="/opt/ec2-demo"
CONF_DIR="/etc/ec2-demo"
STATE_DIR="/var/lib/ec2-demo"
LOG_FILE="/var/log/ec2-demo/redeploy.log"

mkdir -p "$(dirname "$LOG_FILE")"
touch "$LOG_FILE"
chown lightning-server:lightning-server "$LOG_FILE"
exec > >(tee -a "$LOG_FILE" | logger -t lightning-server-redeploy -s) 2>&1

log "Redeploy started at $(date)"
install -d -o root -g root -m 0755 "$APP_DIR"
install -d -o root -g lightning-server -m 0750 "$CONF_DIR"
install -d -o lightning-server -g lightning-server -m 0700 "$STATE_DIR"

download_with_retry() {
    local src="$1" dst="$2" max=5 attempt=1
    while [ $attempt -le $max ]; do
        log "Downloading $src (attempt $attempt/$max)"
        if aws s3 cp "$src" "$dst" --region "$REGION" --no-progress; then return 0; fi
        sleep $((attempt * 5))
        attempt=$((attempt + 1))
    done
    err "Failed to download $src after $max attempts"
    return 1
}

# Roll back to the previously-working server + settings, then restart. Used when the new
# version fails to become active so the instance self-heals instead of staying down.
rollback() {
    err "Rolling back to previous version"
    [ -d "$APP_DIR/server-old" ] && { rm -rf "$APP_DIR/server"; mv "$APP_DIR/server-old" "$APP_DIR/server"; }
    [ -f "$CONF_DIR/settings.enc.old" ] && mv -f "$CONF_DIR/settings.enc.old" "$CONF_DIR/settings.enc"
    systemctl restart ec2-demo || true
}

download_with_retry "s3://$BUCKET/server.zip" "$APP_DIR/server.zip"
[ -d "$APP_DIR/server-old" ] && rm -rf "$APP_DIR/server-old"
[ -d "$APP_DIR/server" ] && mv "$APP_DIR/server" "$APP_DIR/server-old"
log "Successful Download"
log "Unzipping server.zip"
unzip -q "$APP_DIR/server.zip" -d "$APP_DIR"
rm -f "$APP_DIR/server.zip"
# The build stays root-owned so the service can run it but never modify it. Zip entries can carry
# arbitrary modes, so normalize to readable (and executable where already so) and root-writable only.
chown -R root:root "$APP_DIR"
chmod -R u=rwX,go=rX "$APP_DIR"

# Settings stay encrypted at rest; the service decrypts them in memory at startup.
download_with_retry "s3://$BUCKET/settings.enc" "$CONF_DIR/settings.enc.new"
SETTINGS_PASS=$(aws ssm get-parameter --name "$SSM_PARAM" --with-decryption --query Parameter.Value --output text --region "$REGION")
# Test-decrypt (output discarded) so a bad password or corrupt file fails the deploy here rather than
# at service start. The password goes via openssl's environment, not argv: argv is world-readable.
if ! SETTINGS_PASS="$SETTINGS_PASS" openssl enc -d -aes-256-cbc -pbkdf2 -iter 10000 -md sha256 \
    -in "$CONF_DIR/settings.enc.new" -out /dev/null -pass env:SETTINGS_PASS; then
    err "Failed to decrypt settings"
    rm -f "$CONF_DIR/settings.enc.new"
    exit 1
fi
chown root:lightning-server "$CONF_DIR/settings.enc.new"
chmod 640 "$CONF_DIR/settings.enc.new"
log "Verified settings decrypt"
[ -f "$CONF_DIR/settings.enc.old" ] && rm "$CONF_DIR/settings.enc.old"
[ -f "$CONF_DIR/settings.enc" ] && mv "$CONF_DIR/settings.enc" "$CONF_DIR/settings.enc.old"
mv "$CONF_DIR/settings.enc.new" "$CONF_DIR/settings.enc"

# Mount point for the unit's read-only settings bind mount. Created as the service user: it owns
# this directory, so root must not follow anything that may have been placed here.
[ -e "$STATE_DIR/settings.json" ] || sudo -u lightning-server touch "$STATE_DIR/settings.json"

log "Restarting Lightning Server EC2 Demo"
systemctl restart ec2-demo
sleep 5
if ! systemctl is-active --quiet ec2-demo; then
    err "Service failed to start"
    tail -n 100 /var/log/ec2-demo/server.log >&2 || true
    rollback
    exit 1
fi

log "Waiting for liveness at http://localhost:8080/meta/online"
healthy=0
for i in $(seq 1 20); do
    if curl -fsS -o /dev/null --max-time 5 "http://localhost:8080/meta/online"; then healthy=1; break; fi
    sleep 3
done
if [ "$healthy" -ne 1 ]; then
    err "Liveness endpoint http://localhost:8080/meta/online never returned success"
    tail -n 100 /var/log/ec2-demo/server.log >&2 || true
    rollback
    exit 1
fi
log "Done"
REDEPLOY_EOF

chmod +x /usr/local/bin/lightning-server-redeploy
echo "[INFO] Creating Lightning Server Redeploy Script - DONE"

# === Lightning Server Pre-Deploy Script ===
# Runs pre-deploy tasks with the NEW build in a scratch dir, without touching the live server, so
# the current version keeps serving while migrations run. Invoked once per deploy (before the
# redeploy/cutover) - by the single-instance redeploy on the one box, and by the scaling fleet
# script on one instance. A non-zero exit aborts the deploy.
echo "[INFO] Creating Lightning Server Pre-Deploy Script"
cat > /usr/local/bin/lightning-server-predeploy << 'PREDEPLOY_EOF'
#!/bin/bash
set -euo pipefail

log() { echo "[lightning-server-predeploy] $*"; }
err() { echo "[lightning-server-predeploy] ERROR: $*" >&2; }

BUCKET="${deployment_bucket}"
REGION="us-west-2"
SSM_PARAM="/ec2-demo/settings-password"
LOG_FILE="/var/log/ec2-demo/predeploy.log"

mkdir -p "$(dirname "$LOG_FILE")"
touch "$LOG_FILE"
chown lightning-server:lightning-server "$LOG_FILE"
exec > >(tee -a "$LOG_FILE" | logger -t lightning-server-predeploy -s) 2>&1

log "Pre-deploy started at $(date)"

# Fetch the NEW build + settings into a scratch dir; the live server is never touched. mktemp makes a
# fresh root-only directory with an unguessable name: a fixed path under world-writable /var/tmp could
# be pre-created or symlinked by another user to redirect root's writes.
SCRATCH=$(mktemp -d "/var/tmp/ec2-demo-predeploy.XXXXXX")
trap 'cd /; rm -rf "$SCRATCH"' EXIT
aws s3 cp "s3://$BUCKET/server.zip" "$SCRATCH/server.zip" --region "$REGION" --no-progress
unzip -q "$SCRATCH/server.zip" -d "$SCRATCH"

# Encrypted bytes under the name the app loads; the app decrypts them in memory (password passed below).
aws s3 cp "s3://$BUCKET/settings.enc" "$SCRATCH/settings.json" --region "$REGION" --no-progress
SETTINGS_PASS=$(aws ssm get-parameter --name "$SSM_PARAM" --with-decryption --query Parameter.Value --output text --region "$REGION")
if ! SETTINGS_PASS="$SETTINGS_PASS" openssl enc -d -aes-256-cbc -pbkdf2 -iter 10000 -md sha256 \
    -in "$SCRATCH/settings.json" -out /dev/null -pass env:SETTINGS_PASS; then
    err "Failed to decrypt settings"
    exit 1
fi
chown -R lightning-server:lightning-server "$SCRATCH"
chmod 600 "$SCRATCH/settings.json"

# Cap heap so this runs alongside the live server without risking OOM. Heavy migrations may need more.
log "Running pre-deploy tasks with the new version"
cd "$SCRATCH"
if LIGHTNING_SERVER_SETTINGS_DECRYPTION="$SETTINGS_PASS" runuser -u lightning-server -- env "JAVA_OPTS=-Xmx512m" ./server/bin/server predeploy; then
    cd /
    rm -rf "$SCRATCH"
    log "Pre-deploy complete"
else
    cd /
    rm -rf "$SCRATCH"
    err "Pre-deploy tasks failed"
    exit 1
fi
PREDEPLOY_EOF

chmod +x /usr/local/bin/lightning-server-predeploy
echo "[INFO] Creating Lightning Server Pre-Deploy Script - DONE"

# === Settings Key Script (ExecStartPre) ===
# Runs as root before each service start: fetches the settings password from SSM into a RAM-backed,
# root-only environment file that systemd hands to the app. Nothing is written to disk.
echo "[INFO] Creating settings key script"
cat > /usr/local/bin/ec2-demo-settings-key << 'SETTINGS_KEY_EOF'
#!/bin/bash
set -euo pipefail
# systemd runs this root step with the service's environment and working directory, both pointing at
# /var/lib/ec2-demo, which the service can write. Pin them so the service cannot steer root's aws
# CLI (e.g. with a planted ~/.aws/config credential_process); the instance role supplies credentials.
cd /
export HOME=/root AWS_CONFIG_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=/dev/null
BUCKET="${deployment_bucket}"
REGION="us-west-2"
SSM_PARAM="/ec2-demo/settings-password"
SECRETS_DIR="/run/ec2-demo-secrets"
install -d -o root -g root -m 0700 "$SECRETS_DIR"
SETTINGS_PASS=$(aws ssm get-parameter --name "$SSM_PARAM" --with-decryption --query Parameter.Value --output text --region "$REGION")
umask 077
# printf is a shell builtin, so the password never appears in any process's argv. Single quotes make
# systemd take the value literally; the password's charset (letters, digits, '-', '_') contains none.
printf "LIGHTNING_SERVER_SETTINGS_DECRYPTION='%s'\n" "$SETTINGS_PASS" > "$SECRETS_DIR/settings.env.tmp"
mv -f "$SECRETS_DIR/settings.env.tmp" "$SECRETS_DIR/settings.env"
SETTINGS_KEY_EOF
chmod 0700 /usr/local/bin/ec2-demo-settings-key


# === Instance Emergency Update Script ===
# The "a CVE landed and we are not waiting for a rebuild" lever. On a scaling fleet
# update-fleet.sh drives it one drained instance at a time; routine patching there still
# comes from rebuilding the image and rolling the ASG.
#
# It restarts the app unconditionally (the JRE is a system package and may be replaced
# underneath a running JVM), so it is only interruption-free on an instance already
# drained from the load balancer.
#
# Usage: os-update [package ...]
#   with no arguments, applies a full upgrade; otherwise upgrades only the named packages.
echo "[INFO] Creating OS Emergency Update Script"
cat > /usr/local/bin/os-update << 'UPDATE_EOF'
#!/bin/bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

log() { echo "[os-update] $(date '+%Y-%m-%d %H:%M:%S') $*"; }
err() { echo "[os-update] $(date '+%Y-%m-%d %H:%M:%S') ERROR: $*" >&2; }

LOG_FILE="/var/log/ec2-demo/os-update.log"
PACKAGES="$*"

mkdir -p "$(dirname "$LOG_FILE")"
touch "$LOG_FILE"
chown lightning-server:lightning-server "$LOG_FILE"
exec > >(tee -a "$LOG_FILE" | logger -t os-update -s) 2>&1

log "OS update started"
# The repositories are locked to the release this host is on, so a plain upgrade only sees fixes
# already in that release; every fix published since would be silently missed while the update
# reported success. --releasever=latest moves to the newest release first.
DNF_OPTS="-y --releasever=latest"
if [ -n "$PACKAGES" ]; then
    # `dnf upgrade` exits 0 for a package that is not installed, so a typo'd or wrong-architecture
    # name would report a clean patch while changing nothing at all. Check each name up front and
    # fail loudly instead.
    for p in $PACKAGES; do
        if ! rpm -q "$p" >/dev/null 2>&1; then
            err "not installed on this host, refusing to report a successful update: $p"
            exit 1
        fi
    done
    log "Upgrading only: $PACKAGES"
    dnf $DNF_OPTS upgrade $PACKAGES
else
    log "Applying full upgrade"
    dnf $DNF_OPTS upgrade
    # Deliberately only on the full-upgrade path. Naming packages means the operator asked for a minimal,
    # auditable change to one or two packages; autoremove is a whole-system operation, which is not a
    # blast radius to take on while chasing a single CVE.
    dnf -y autoremove
fi

log "Restarting Lightning Server EC2 Demo"
systemctl restart ec2-demo
sleep 5
if ! systemctl is-active --quiet ec2-demo; then
    err "Service failed to start after OS update"
    tail -n 100 /var/log/ec2-demo/server.log >&2 || true
    exit 1
fi

log "Waiting for liveness at http://localhost:8080/meta/online"
healthy=0
for i in $(seq 1 20); do
    if curl -fsS -o /dev/null --max-time 5 "http://localhost:8080/meta/online"; then healthy=1; break; fi
    sleep 3
done
if [ "$healthy" -ne 1 ]; then
    err "Liveness endpoint http://localhost:8080/meta/online never returned success"
    tail -n 100 /var/log/ec2-demo/server.log >&2 || true
    exit 1
fi

# Breadcrumb for the CloudWatch log; a reboot is needed to activate a patched kernel. Callers that
# act on it (update-fleet.sh) run their own reboot check over SSM rather than reading this back,
# because SSM truncates command output at 24,000 characters per stream and a full upgrade can push
# a trailing marker past that cap. needs-restarting exits 1 when a reboot is needed.
if dnf needs-restarting -r >/dev/null 2>&1; then log "REBOOT_REQUIRED=no"; else log "REBOOT_REQUIRED=yes"; fi
log "Done"
UPDATE_EOF

chmod +x /usr/local/bin/os-update
echo "[INFO] Creating OS Emergency Update Script - DONE"



echo "[INFO] Reloading systemd configuration..."
systemctl daemon-reload

systemctl enable ec2-demo

# First-time deploy uses the same script that subsequent SSM-driven redeploys
# will use, so there's exactly one code path that knows how to deploy the server.
echo "[INFO] Running first-time application deploy..."
/usr/local/bin/lightning-server-redeploy

# === Start Services ===
echo "[INFO] Starting services..."
systemctl enable nginx
systemctl restart nginx


# Verify CloudWatch agent started (non-fatal if it fails)
if ! systemctl is-active --quiet amazon-cloudwatch-agent; then
    echo "[WARN] CloudWatch agent failed to start. Logs may not be collected."
    echo "[WARN] Check: journalctl -u amazon-cloudwatch-agent"
fi

BOOT_END=$(date +%s)
BOOT_DURATION=$((BOOT_END - BOOT_START))
echo "[INFO] EC2 Init script completed in $BOOT_DURATION seconds"

