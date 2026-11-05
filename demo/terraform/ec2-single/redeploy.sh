#!/usr/bin/env bash
# Triggered by null_resource.redeploy_app when the jar or settings hash
# changes. Tells the running EC2 instance to run /usr/local/bin/lightning-server-redeploy
# (installed by ec2_init.sh) via SSM Run Command. The on-instance script does
# the actual stop/fetch/decrypt/start work.
set -euo pipefail

INSTANCE_ID="${1:?usage: redeploy.sh <instance-id> [region]}"
REGION="${2:-us-west-2}"

log() { echo "[redeploy] $*"; }
err() { echo "[redeploy] ERROR: $*" >&2; }

log "Waiting for SSM agent on $INSTANCE_ID..."
for i in $(seq 1 60); do
    status=$(aws ssm describe-instance-information \
        --filters "Key=InstanceIds,Values=$INSTANCE_ID" \
        --region "$REGION" \
        --query 'InstanceInformationList[0].PingStatus' \
        --output text 2>/dev/null || echo "None")
    if [ "$status" = "Online" ]; then
        log "SSM agent online"
        break
    fi
    if [ "$i" = "60" ]; then
        err "SSM agent never reported Online for $INSTANCE_ID (last status: $status)"
        exit 1
    fi
    sleep 5
done

# Wait for cloud-init (ec2_init.sh) to finish so the redeploy scripts are
# guaranteed to be installed. cloud-init returns 2 for a completed-but-
# degraded boot; accept that outcome only when both required scripts exist.
log "Waiting for cloud-init to complete on $INSTANCE_ID..."
WAIT_CMD_ID=$(aws ssm send-command \
    --instance-ids "$INSTANCE_ID" \
    --document-name "AWS-RunShellScript" \
    --comment "terraform redeploy: wait for cloud-init" \
    --parameters 'commands=["set +e; cloud-init status --wait; cloud_init_exit=$?; set -e; if [ \"$cloud_init_exit\" -eq 0 ]; then exit 0; fi; if [ \"$cloud_init_exit\" -eq 2 ] && [ -x /usr/local/bin/lightning-server-redeploy ] && [ -x /usr/local/bin/lightning-server-predeploy ]; then echo \"cloud-init completed degraded; required deployment scripts are present\"; exit 0; fi; echo \"cloud-init did not complete provisioning (exit $cloud_init_exit)\" >&2; exit \"$cloud_init_exit\""],executionTimeout=900' \
    --region "$REGION" \
    --query 'Command.CommandId' \
    --output text)

for i in $(seq 1 180); do
    status=$(aws ssm get-command-invocation \
        --command-id "$WAIT_CMD_ID" \
        --instance-id "$INSTANCE_ID" \
        --region "$REGION" \
        --query 'Status' \
        --output text 2>/dev/null || echo "Pending")
    case "$status" in
        Success)
            log "cloud-init complete"
            break
            ;;
        Cancelled|Failed|TimedOut)
            err "cloud-init wait finished with status: $status"
            aws ssm get-command-invocation \
                --command-id "$WAIT_CMD_ID" \
                --instance-id "$INSTANCE_ID" \
                --region "$REGION" \
                --query 'StandardErrorContent' \
                --output text >&2 || true
            exit 1
            ;;
        *)
            sleep 5
            ;;
    esac
    if [ "$i" = "180" ]; then
        err "cloud-init did not complete within polling window"
        exit 1
    fi
done

# Run pre-deploy tasks first (new build in a scratch dir; the running server is untouched). If they
# fail, abort without redeploying so the current version keeps serving.
log "Sending SSM command to run /usr/local/bin/lightning-server-predeploy..."
PRE_CMD_ID=$(aws ssm send-command \
    --instance-ids "$INSTANCE_ID" \
    --document-name "AWS-RunShellScript" \
    --comment "terraform pre-deploy" \
    --parameters 'commands=/usr/local/bin/lightning-server-predeploy,executionTimeout=600' \
    --region "$REGION" \
    --query 'Command.CommandId' \
    --output text)
log "Pre-deploy command ID: $PRE_CMD_ID — polling for completion..."
for i in $(seq 1 180); do
    status=$(aws ssm get-command-invocation \
        --command-id "$PRE_CMD_ID" \
        --instance-id "$INSTANCE_ID" \
        --region "$REGION" \
        --query 'Status' \
        --output text 2>/dev/null || echo "Pending")
    case "$status" in
        Success)
            log "Pre-deploy succeeded"
            break
            ;;
        Cancelled|Failed|TimedOut)
            err "Pre-deploy finished with status: $status — aborting without redeploy"
            aws ssm get-command-invocation \
                --command-id "$PRE_CMD_ID" \
                --instance-id "$INSTANCE_ID" \
                --region "$REGION" \
                --query 'StandardErrorContent' \
                --output text >&2 || true
            exit 1
            ;;
        *)
            sleep 5
            ;;
    esac
    if [ "$i" = "180" ]; then
        err "Pre-deploy did not finish within polling window"
        exit 1
    fi
done

log "Sending SSM command to run /usr/local/bin/lightning-server-redeploy..."
CMD_ID=$(aws ssm send-command \
    --instance-ids "$INSTANCE_ID" \
    --document-name "AWS-RunShellScript" \
    --comment "terraform redeploy: jar/settings refresh" \
    --parameters 'commands=/usr/local/bin/lightning-server-redeploy,executionTimeout=600' \
    --region "$REGION" \
    --query 'Command.CommandId' \
    --output text)

log "Command ID: $CMD_ID — polling for completion..."
for i in $(seq 1 180); do
    status=$(aws ssm get-command-invocation \
        --command-id "$CMD_ID" \
        --instance-id "$INSTANCE_ID" \
        --region "$REGION" \
        --query 'Status' \
        --output text 2>/dev/null || echo "Pending")
    case "$status" in
        Success)
            log "Command succeeded"
            aws ssm get-command-invocation \
                --command-id "$CMD_ID" \
                --instance-id "$INSTANCE_ID" \
                --region "$REGION" \
                --query 'StandardOutputContent' \
                --output text
            exit 0
            ;;
        Cancelled|Failed|TimedOut)
            err "SSM command finished with status: $status"
            echo "--- stdout ---" >&2
            aws ssm get-command-invocation \
                --command-id "$CMD_ID" \
                --instance-id "$INSTANCE_ID" \
                --region "$REGION" \
                --query 'StandardOutputContent' \
                --output text >&2 || true
            echo "--- stderr ---" >&2
            aws ssm get-command-invocation \
                --command-id "$CMD_ID" \
                --instance-id "$INSTANCE_ID" \
                --region "$REGION" \
                --query 'StandardErrorContent' \
                --output text >&2 || true
            exit 1
            ;;
        *)
            sleep 5
            ;;
    esac
done

err "SSM command did not finish within polling window"
exit 1