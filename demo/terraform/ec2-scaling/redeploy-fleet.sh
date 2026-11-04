#!/usr/bin/env bash
# Rolling, in-place redeploy across the Auto Scaling Group. Updates instances in batches of
# $BATCH (default 1; override with LS_REDEPLOY_BATCH for an emergency faster rollout). For each
# instance: drain it from the ALB, run the on-instance redeploy via SSM (which validates the new
# build against the local liveness endpoint and self-rolls-back on failure), and return it to
# service only once the ALB reports it healthy. Any failure halts the rollout so it surfaces to
# `terraform apply`.
#
# The ASG is told to suspend the processes that would otherwise fight us — HealthCheck and
# ReplaceUnhealthy (so it can't terminate a drained instance) and AddToLoadBalancer/AZRebalance.
# An EXIT trap always resumes them and re-registers every in-service instance, so even an abrupt
# termination leaves the fleet serving rather than stranded.
set -euo pipefail

ASG_NAME="${1:?usage: redeploy-fleet.sh <asg-name> <region> <target-group-arn>}"
REGION="${2:?region required}"
TG_ARN="${3:?target group arn required}"
APP_PORT="8080"
BATCH="${LS_REDEPLOY_BATCH:-1}"
SUSPENDED="HealthCheck ReplaceUnhealthy AZRebalance AddToLoadBalancer"

log() { echo "[redeploy-fleet] $*"; }
err() { echo "[redeploy-fleet] ERROR: $*" >&2; }

instance_ids() {
    aws autoscaling describe-auto-scaling-groups \
        --auto-scaling-group-names "$ASG_NAME" \
        --region "$REGION" \
        --query 'AutoScalingGroups[0].Instances[?LifecycleState==`InService`].InstanceId' \
        --output text
}

# Always restore the ASG to a clean state, even on abnormal exit: resume the suspended processes
# and make sure every in-service instance is registered with the target group.
resume_and_restore() {
    log "Resuming ASG processes and re-registering all in-service instances"
    aws autoscaling resume-processes --auto-scaling-group-name "$ASG_NAME" \
        --scaling-processes $SUSPENDED --region "$REGION" || true
    local id
    for id in $(instance_ids); do
        aws elbv2 register-targets --target-group-arn "$TG_ARN" \
            --targets "Id=$id,Port=$APP_PORT" --region "$REGION" || true
    done
}

wait_ssm_online() {
    local id="$1"
    for i in $(seq 1 60); do
        status=$(aws ssm describe-instance-information \
            --filters "Key=InstanceIds,Values=$id" --region "$REGION" \
            --query 'InstanceInformationList[0].PingStatus' --output text 2>/dev/null || echo "None")
        [ "$status" = "Online" ] && return 0
        sleep 5
    done
    err "SSM agent never came Online for $id"
    return 1
}

# Sends one command via SSM, waits for it to finish, and echoes its combined stdout+stderr.
# Returns non-zero if the command failed or never finished within $attempts polls.
#
# Note that SSM returns only the first 24,000 characters of each stream, so anything the caller
# needs a reliable *answer* from (as opposed to a log to read) must print little enough to stay
# well under that cap. See needs_reboot.
#
# The remote command is interpolated into JSON, so it must not contain double quotes or
# backslashes -- use single quotes inside it.
ssm_run() {
    local id="$1" timeout="$2" attempts="$3" comment="$4" remote_cmd="$5"
    local cmd_id status i

    cmd_id=$(aws ssm send-command \
        --instance-ids "$id" \
        --document-name "AWS-RunShellScript" \
        --comment "$comment" \
        --parameters "$(printf '{"commands":["%s"],"executionTimeout":["%s"]}' "$remote_cmd" "$timeout")" \
        --region "$REGION" --query 'Command.CommandId' --output text)

    for i in $(seq 1 "$attempts"); do
        status=$(aws ssm get-command-invocation --command-id "$cmd_id" --instance-id "$id" \
            --region "$REGION" --query 'Status' --output text 2>/dev/null || echo "Pending")
        case "$status" in
            Success)
                aws ssm get-command-invocation --command-id "$cmd_id" --instance-id "$id" \
                    --region "$REGION" --query 'join(``, [StandardOutputContent, StandardErrorContent])' \
                    --output text || true
                return 0 ;;
            Cancelled|Failed|TimedOut)
                err "$comment on $id finished with status: $status"
                aws ssm get-command-invocation --command-id "$cmd_id" --instance-id "$id" \
                    --region "$REGION" --query 'StandardErrorContent' --output text >&2 || true
                return 1 ;;
            *) sleep 5 ;;
        esac
    done
    err "$comment on $id did not finish within polling window"
    return 1
}

trap resume_and_restore EXIT

# The on-instance redeploy script validates the new build itself and self-rolls-back on failure,
# so its output is only interesting when it fails -- and ssm_run already prints stderr for us then.
run_redeploy() {
    ssm_run "$1" 600 180 "terraform rolling redeploy" "/usr/local/bin/lightning-server-redeploy" >/dev/null
}

# Run pre-deploy tasks on one instance via SSM (in a scratch dir; the live server is untouched).
# Returns non-zero on any failure so the rollout can abort before touching the fleet.
run_predeploy() {
    local id="$1"
    local cmd_id
    cmd_id=$(aws ssm send-command \
        --instance-ids "$id" \
        --document-name "AWS-RunShellScript" \
        --comment "terraform pre-deploy" \
        --parameters 'commands=/usr/local/bin/lightning-server-predeploy,executionTimeout=600' \
        --region "$REGION" --query 'Command.CommandId' --output text)
    for i in $(seq 1 180); do
        status=$(aws ssm get-command-invocation --command-id "$cmd_id" --instance-id "$id" \
            --region "$REGION" --query 'Status' --output text 2>/dev/null || echo "Pending")
        case "$status" in
            Success) return 0 ;;
            Cancelled|Failed|TimedOut)
                err "pre-deploy on $id finished with status: $status"
                aws ssm get-command-invocation --command-id "$cmd_id" --instance-id "$id" \
                    --region "$REGION" --query 'StandardErrorContent' --output text >&2 || true
                return 1 ;;
            *) sleep 5 ;;
        esac
    done
    err "pre-deploy on $id did not finish within polling window"
    return 1
}

# Drain -> redeploy -> validate healthy, for one instance. Returns non-zero on any failure.
process_instance() {
    local id="$1"
    log "=== Redeploying $id ==="
    wait_ssm_online "$id" || return 1

    log "Draining $id from the target group"
    aws elbv2 deregister-targets --target-group-arn "$TG_ARN" \
        --targets "Id=$id,Port=$APP_PORT" --region "$REGION"
    aws elbv2 wait target-deregistered --target-group-arn "$TG_ARN" \
        --targets "Id=$id,Port=$APP_PORT" --region "$REGION" || true

    log "Running deploy script"
    if ! run_redeploy "$id"; then
        err "redeploy failed on $id (it self-heals to the previous version)"
        return 1
    fi

    log "Returning $id to service"
    aws elbv2 register-targets --target-group-arn "$TG_ARN" \
        --targets "Id=$id,Port=$APP_PORT" --region "$REGION"
    if ! aws elbv2 wait target-in-service --target-group-arn "$TG_ARN" \
        --targets "Id=$id,Port=$APP_PORT" --region "$REGION"; then
        err "$id did not become healthy after redeploy"
        return 1
    fi
    log "$id healthy"
}

log "Suspending ASG processes during redeploy: $SUSPENDED"
aws autoscaling suspend-processes --auto-scaling-group-name "$ASG_NAME" \
    --scaling-processes $SUSPENDED --region "$REGION"

IDS=$(instance_ids)
if [ -z "$IDS" ]; then
    log "No in-service instances found; nothing to redeploy."
    exit 0
fi

# Run pre-deploy tasks once, on one instance, before rolling the fleet. The whole fleet keeps
# serving the previous version while these run; a failure aborts the rollout (the EXIT trap resumes
# ASG processes and re-registers every instance, so the fleet is left untouched and serving).
FIRST_ID="${IDS%%[[:space:]]*}"
log "Running pre-deploy tasks on $FIRST_ID before rolling the fleet"
wait_ssm_online "$FIRST_ID" || exit 1
if ! run_predeploy "$FIRST_ID"; then
    err "Pre-deploy tasks failed; aborting rollout."
    exit 1
fi

# Process in batches of $BATCH, in parallel within a batch; fail the whole run if any member fails.
batch=()
flush_batch() {
    [ ${#batch[@]} -eq 0 ] && return 0
    local pids=() id p fail=0
    for id in "${batch[@]}"; do process_instance "$id" & pids+=("$!"); done
    for p in "${pids[@]}"; do wait "$p" || fail=1; done
    batch=()
    if [ "$fail" -ne 0 ]; then err "A redeploy in the batch failed; halting rollout."; exit 1; fi
}

for id in $IDS; do
    batch+=("$id")
    if [ ${#batch[@]} -ge "$BATCH" ]; then flush_batch; fi
done
flush_batch

log "Rolling redeploy complete."