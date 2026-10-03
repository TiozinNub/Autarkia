#!/usr/bin/env bash
# CI: wait out a running Anima push build before resolving `anima-<mc>`.
#
# Usage: CI_TOKEN=… GITEA_SERVER=… GITEA_OWNER=… scripts/wait-for-anima.sh "<Anima job name>"
#   the job name is Anima's for the same node: `build (26.1.x)`, or `snapshot`.
#
# A change spanning both mods is two pushes seconds apart — Anima first, then the bump here — so
# both CIs start together and this one can reach dependency resolution while Anima is still
# building. What it resolves then is the PREVIOUS library: usually a confusing compile error, and
# occasionally something worse, a green build against a pair that was never tried.
#
# SNAPSHOTS ONLY. A release pin is immutable and its order is decided by hand — Anima tagged
# first, then this — so there is nothing to wait for.
set -euo pipefail

JOB="${1:?the Anima job to wait for, e.g. 'build (26.1.x)' or 'snapshot'}"
: "${CI_TOKEN:?}" "${GITEA_SERVER:?}" "${GITEA_OWNER:?}"
cd "$(dirname "${BASH_SOURCE[0]}")/.."

pin=$(awk '/^\[/{exit} /^deps\.anima/{sub(/.*= *"/,"");sub(/".*/,"");print;exit}' stonecutter.properties.toml)
case "$pin" in
    *-SNAPSHOT) ;;
    *) echo "deps.anima=$pin is a release — immutable, and ordered by hand. Nothing to wait for."; exit 0 ;;
esac

# A push is the only event that publishes, so a nightly or a dispatch over there is none of this
# build's business. Only Anima's job for THIS node counts: each node publishes its own coordinate
# and this job resolves only that one. Reading the newest row of any node failed every node here
# when a lagging 26.3.x went red (2026-09-27).
state() {
    curl -fsS -H "Authorization: token $CI_TOKEN" \
        "$GITEA_SERVER/api/v1/repos/$GITEA_OWNER/Anima/actions/tasks?limit=20" \
    | JOB="$JOB" python3 -c 'import os,sys,json; job=os.environ["JOB"]; rows=[r for r in json.load(sys.stdin).get("workflow_runs",[]) if r["head_branch"]=="main" and r["event"]=="push" and r["name"]==job]; live=[r for r in rows if r["status"] in ("waiting","running","in_progress")]; print("live "+live[0]["head_sha"][:8] if live else (rows[0]["status"]+" "+rows[0]["head_sha"][:8] if rows else "idle -"))'
}

waited=0
deadline=$(( SECONDS + 1200 ))
while :; do
    read -r st sha <<< "$(state || true)"
    case "${st:-}" in
        live)
            if (( SECONDS >= deadline )); then
                echo "::error::Anima ($sha) has been building for 20 minutes; this build would resolve a stale $pin"
                exit 1
            fi
            waited=1
            echo "Anima $sha is still building — waiting for it to publish…"
            sleep 20
            ;;
        # An unanswered API call is not a conclusion. Reading it as one would fail this build
        # with a quoted empty status, which says nothing about what went wrong.
        "")
            if (( SECONDS >= deadline )); then
                echo "::error::no answer from the Anima API for 20 minutes; refusing to guess whether $pin is current"
                exit 1
            fi
            echo "no answer from the Anima API — retrying"
            sleep 20
            ;;
        # Only fatal if we actually waited for THAT run. A build that went red an hour ago is a
        # pre-existing condition and none of this build's business — the pin resolves whatever
        # was last published successfully, exactly as it did before this step.
        success|idle)
            echo "Anima is idle (last push: $st $sha) — resolving $pin now"
            break
            ;;
        *)
            if (( waited )); then
                echo "::error::the Anima build this one waited for ($sha) ended '$st', so $pin was never published from it"
                exit 1
            fi
            echo "Anima's last push build ($sha) ended '$st' before this one started — resolving whatever it published last"
            break
            ;;
    esac
done
