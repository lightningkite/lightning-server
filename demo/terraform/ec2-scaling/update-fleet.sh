#!/usr/bin/env bash
# Rolling, in-place Emergency OS package update across the Auto Scaling Group. This is the "a CVE just
# landed and we are not waiting for an AMI bake" lever -- routine patching is meant to come from
# rebuilding the image and letting the ASG instance refresh roll the fleet, which also fixes the
# baseline that new scale-out instances launch from. This script does NOT update the AMI, so any
# instance the ASG launches afterwards is still unpatched until the next image rebuild.
#
# The ASG is told to suspend the processes that would otherwise fight us -- HealthCheck and
# ReplaceUnhealthy (so it can't terminate a drained instance) and AddToLoadBalancer/AZRebalance.
# An EXIT trap always resumes them and re-registers every in-service instance, so even an abrupt
# termination leaves the fleet serving rather than stranded.
set -euo pipefail

APP_PORT="8080"
SUSPENDED="HealthCheck ReplaceUnhealthy AZRebalance AddToLoadBalancer"

log() { echo "[update-fleet] $*"; }
err() { echo "[update-fleet] ERROR: $*" >&2; }

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

usage() {
    cat <<'USAGE'
usage: update-fleet.sh <asg-name> <region> <target-group-arn> [options]

Options:
  -b, --batch N          instances to update in parallel (default 1)
  -p, --packages "LIST"  space-separated packages to upgrade; omit for a full upgrade
  -n, --no-reboot        leave instances that report reboot-required un-rebooted; they are
                         still probed, and listed at the end of the run
  -f, --force            allow a --batch that covers the whole fleet (accepts an outage)
  -h, --help             show this help

Example:
  bash update-fleet.sh altitude-production us-west-2 "$TG_ARN" -p "openssl libssl3"
USAGE
}

die() { err "$*"; echo >&2; usage >&2; exit 2; }

BATCH=1
PACKAGES=""
REBOOT=1
FORCE=0
POSITIONAL=()

while [ $# -gt 0 ]; do
    case "$1" in
        -b|--batch)    [ $# -ge 2 ] || die "$1 requires a value"; BATCH="$2"; shift 2 ;;
        -p|--packages) [ $# -ge 2 ] || die "$1 requires a value"; PACKAGES="$2"; shift 2 ;;
        -n|--no-reboot) REBOOT=0; shift ;;
        -f|--force)    FORCE=1; shift ;;
        -h|--help)     usage; exit 0 ;;
        --)            shift; POSITIONAL+=("$@"); break ;;
        -*)            die "unknown option: $1" ;;
        *)             POSITIONAL+=("$1"); shift ;;
    esac
done

[ ${#POSITIONAL[@]} -eq 3 ] || die "expected 3 arguments (asg-name, region, target-group-arn), got ${#POSITIONAL[@]}"
ASG_NAME="${POSITIONAL[0]}"
REGION="${POSITIONAL[1]}"
TG_ARN="${POSITIONAL[2]}"

# Validated up front because a non-numeric batch would otherwise only surface deep in flush_batch,
# after the ASG processes have already been suspended.
case "$BATCH" in ''|*[!0-9]*) die "--batch must be a positive integer, got: $BATCH" ;; esac
[ "$BATCH" -ge 1 ] || die "--batch must be at least 1"

# --packages is interpolated into the SSM parameters JSON and then run as a root shell command on
# every instance, so it is restricted to the characters real package specs need: names, versions
# (=), and architecture qualifiers (:). That keeps a stray quote from producing malformed JSON and
# a stray `;` from turning a typo into a fleet-wide root command. This is a guard against mistakes
# rather than a security boundary -- whoever runs this already holds credentials that can do worse.
PACKAGES_RE='^[a-zA-Z0-9._+:=~-]+([ ]+[a-zA-Z0-9._+:=~-]+)*$'
if [ -n "$PACKAGES" ] && ! [[ "$PACKAGES" =~ $PACKAGES_RE ]]; then
    die "--packages must be a space-separated list of package names, got: $PACKAGES"
fi

# Runs /usr/local/bin/os-update, baked into the AMI by image_data.yaml, and
# echoes its combined output so the caller can inspect it. The updater logs through `logger -s`,
# so most of its output arrives on stderr rather than stdout.
run_update() {
    local id="$1"
    local remote_cmd="/usr/local/bin/os-update"
    [ -n "$PACKAGES" ] && remote_cmd="$remote_cmd $PACKAGES"
    ssm_run "$id" 1800 360 "os update" "$remote_cmd"
}

# Asks the instance directly whether a reboot is pending. Deliberately its own tiny command rather
# than a marker grepped out of the updater's output: a full apt upgrade easily exceeds the 24,000
# characters SSM returns per stream, and a truncated trailing marker would look exactly like "no
# reboot needed" -- silently leaving a patched kernel un-activated, which is the one thing this
# script exists to prevent.
#
# 0 = reboot required, 1 = not required, 2 = could not determine.
needs_reboot() {
    local id="$1" out
    out=$(ssm_run "$id" 60 24 "reboot check" \
        "dnf needs-restarting -r >/dev/null 2>&1 && echo REBOOT_REQUIRED=no || echo REBOOT_REQUIRED=yes") || return 2
    case "$out" in
        *REBOOT_REQUIRED=yes*) return 0 ;;
        *REBOOT_REQUIRED=no*)  return 1 ;;
        *) err "unexpected reboot-check output from $id: $out"; return 2 ;;
    esac
}

# Boot id changes on every boot, so comparing it across a reboot is what actually proves the
# instance cycled. Echoes an empty string if the instance cannot be reached.
boot_id() {
    local id="$1" out
    out=$(ssm_run "$id" 60 12 "boot id" "cat /proc/sys/kernel/random/boot_id" 2>/dev/null) || out=""
    printf '%s' "$out" | tr -d '[:space:]'
}

# The reboot command backgrounds itself so SSM can record it before the instance goes down. Safe
# here because the instance is drained and the ASG cannot replace it.
reboot_instance() {
    local id="$1" before after i

    before=$(boot_id "$id")
    if [ -z "$before" ]; then
        err "could not read the boot id for $id; refusing to reboot without a way to verify it"
        return 1
    fi

    log "Rebooting $id (boot id $before)"
    if ! ssm_run "$id" 60 12 "reboot" \
        "nohup bash -c 'sleep 3; systemctl reboot' >/dev/null 2>&1 &" >/dev/null; then
        err "failed to send the reboot command to $id"
        return 1
    fi

    # Poll until the boot id actually changes. Waiting for SSM to report Online is not enough on
    # its own: the pre-reboot agent keeps answering until the instance finishes shutting down, so
    # a slow shutdown would let us continue against an instance still running the old kernel --
    # and a reboot that never happened at all would look identical.
    for i in $(seq 1 60); do
        sleep 10
        after=$(boot_id "$id")
        if [ -n "$after" ] && [ "$after" != "$before" ]; then
            log "$id back online after reboot (boot id $before -> $after)"
            return 0
        fi
    done

    err "$id never reported a new boot id; it does not appear to have rebooted"
    return 1
}

# Drain -> update -> (reboot) -> validate healthy, for one instance. Returns non-zero on failure.
process_instance() {
    local id="$1"
    log "=== Updating $id ==="
    wait_ssm_online "$id" || return 1

    log "Draining $id from the target group"
    aws elbv2 deregister-targets --target-group-arn "$TG_ARN" \
        --targets "Id=$id,Port=$APP_PORT" --region "$REGION"
    aws elbv2 wait target-deregistered --target-group-arn "$TG_ARN" \
        --targets "Id=$id,Port=$APP_PORT" --region "$REGION" || true

    log "Running update script"
    local output
    if ! output=$(run_update "$id"); then
        err "os update failed on $id; halting before it is returned to service"
        return 1
    fi
    echo "$output"

    # Probed unconditionally, including under --no-reboot: "which instances are still running the
    # old kernel" is the fact this whole script exists to establish, and skipping the check when we
    # are not going to act on it is how a half-patched fleet gets mistaken for a patched one.
    local state=0
    needs_reboot "$id" || state=$?
    case "$state" in
        0)  if [ "$REBOOT" != "0" ]; then
                reboot_instance "$id" || return 1
            else
                log "$id needs a reboot; leaving it running the old kernel (--no-reboot)"
                echo "$id" >> "$PENDING_REBOOT_FILE"
            fi ;;
        1)  log "$id does not require a reboot" ;;
        # Undetermined is fatal only when we were going to reboot -- continuing there would mean
        # returning a possibly-unpatched instance to service while believing it was handled. Under
        # --no-reboot nothing was going to happen anyway, so record it and carry on.
        *)  if [ "$REBOOT" != "0" ]; then
                err "could not determine whether $id needs a reboot; halting rather than guessing"
                return 1
            fi
            err "could not determine whether $id needs a reboot; assuming it does"
            echo "$id" >> "$PENDING_REBOOT_FILE" ;;
    esac

    log "Returning $id to service"
    aws elbv2 register-targets --target-group-arn "$TG_ARN" \
        --targets "Id=$id,Port=$APP_PORT" --region "$REGION"
    if ! aws elbv2 wait target-in-service --target-group-arn "$TG_ARN" \
        --targets "Id=$id,Port=$APP_PORT" --region "$REGION"; then
        err "$id did not become healthy after the os update"
        return 1
    fi
    log "$id healthy"
}

IDS=$(instance_ids)
if [ -z "$IDS" ]; then
    log "No in-service instances found; nothing to update."
    exit 0
fi

# A batch that covers the whole fleet drains every instance at once -- an outage, not a rolling
# update. The steady-state ASG runs two instances, so `-b 2` is already the entire fleet, which is
# not obvious from a flag that just says "in parallel". Checked before anything is suspended or
# drained, so refusing costs nothing.
#
# Only an avoidable outage is refused. With a single instance in service there is no batch size
# that avoids downtime, so that case warns and proceeds rather than blocking an emergency patch
# behind a flag the operator never set.
COUNT=$(echo $IDS | wc -w)
if [ "$COUNT" -eq 1 ]; then
    log "WARNING: only one instance is in service, so updating it means a brief outage."
elif [ "$BATCH" -ge "$COUNT" ] && [ "$FORCE" != "1" ]; then
    err "--batch $BATCH covers all $COUNT in-service instances: every instance would be drained at"
    err "once, taking the service fully down. Use a smaller --batch, or --force if that is intended."
    exit 2
fi

# Instances that finish still needing a reboot are recorded in a file rather than a variable:
# process_instance runs in a background subshell, so an assignment there would never make it back
# to the parent. Created here, next to the trap that cleans it up.
PENDING_REBOOT_FILE=$(mktemp)

report_pending_reboots() {
    if [ -s "$PENDING_REBOOT_FILE" ]; then
        err "These instances were patched but are still running the old kernel:"
        while read -r pending; do err "  $pending"; done < "$PENDING_REBOOT_FILE"
        err "Re-run without --no-reboot, or reboot them yourself, before calling the fleet patched."
    fi
    rm -f "$PENDING_REBOOT_FILE"
}

# Installed only now so the early exits above don't log a spurious restore of state never touched.
trap 'resume_and_restore; report_pending_reboots' EXIT

log "Suspending ASG processes during update: $SUSPENDED"
aws autoscaling suspend-processes --auto-scaling-group-name "$ASG_NAME" \
    --scaling-processes $SUSPENDED --region "$REGION"

# Process in batches of $BATCH, in parallel within a batch; fail the whole run if any member fails.
batch=()
flush_batch() {
    [ ${#batch[@]} -eq 0 ] && return 0
    local pids=() id p fail=0
    for id in "${batch[@]}"; do process_instance "$id" & pids+=("$!"); done
    for p in "${pids[@]}"; do wait "$p" || fail=1; done
    batch=()
    if [ "$fail" -ne 0 ]; then err "An update in the batch failed; halting rollout."; exit 1; fi
}

for id in $IDS; do
    batch+=("$id")
    if [ ${#batch[@]} -ge "$BATCH" ]; then flush_batch; fi
done
flush_batch

log "Fleet os update complete."
log "NOTE: the AMI is unchanged. Instances launched by scale-out are still unpatched until the"
log "      image is rebuilt. Treat this as a stopgap, not the patch of record."