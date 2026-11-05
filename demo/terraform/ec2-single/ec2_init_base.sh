#!/bin/bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

export BOOT_START=$(date +%s)

# === Logging Setup ===
WORKING_DIR="/var/lib/ec2-demo-init"
mkdir -p -m 0700 "$WORKING_DIR"
cd "$WORKING_DIR"

mkdir -p /var/log/ec2-demo
touch /var/log/ec2-demo/ec2_init.log
exec > >(tee /var/log/ec2-demo/ec2_init.log | logger -t ec2_init -s) 2>&1

# === Bootstrap packages ===
# Just enough to fetch ec2_init.sh; the full system update and package install happen there.
echo "[INFO] Installing bootstrap packages..."
dnf install -y unzip


# === Install AWS CLI v2 ===
echo "[INFO] Installing AWS CLI V2 at $(date)"
curl https://awscli.amazonaws.com/awscli-exe-linux-aarch64.zip -o "awscliv2.zip"
unzip -q awscliv2.zip
./aws/install
rm -rf aws/ awscliv2.zip



download_with_retry() {
    local src="$1" dst="$2" max=5 attempt=1
    while [ $attempt -le $max ]; do
        echo "[INFO] Downloading $src (attempt $attempt/$max)"
        if aws s3 cp "$src" "$dst" --region "us-west-2" --no-progress; then return 0; fi
        sleep $((attempt * 5))
        attempt=$((attempt + 1))
    done
    echo "[ERROR] Failed to download $src after $max attempts"
    return 1
}

echo "[INFO] Downloading EC2 Init script at $(date)"
download_with_retry "s3://${deployment_bucket}/${deployment_script_key}" "ec2_init.sh"
chmod +x ec2_init.sh

echo "[INFO] Running EC2 Init script at $(date)"
./ec2_init.sh
rm ec2_init.sh

