#!/bin/bash
set -euo pipefail
mkdir -p /etc/lightning-server
cat > /etc/lightning-server/deploy.env << 'DEPLOY_ENV_EOF'
DEPLOYMENT_BUCKET=${deployment_bucket}
AWS_REGION_NAME=${aws_region}
DEPLOY_ENV_EOF

# Fetch the latest application + settings and start the service. The baked redeploy script
# rolls back to the previous version on failure; on a brand-new instance there is nothing to
# roll back to, so a hard failure here leaves the instance unhealthy and the ASG replaces it.
/usr/local/bin/lightning-server-redeploy
systemctl enable LightningServerEC2SDemo || true