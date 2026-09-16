#!/usr/bin/env bash
# Boot a REAL Fabric dedicated server with this build's jar and the Anima it compiled against, and
# prove a Person lives.
#
# Usage: scripts/smoke.sh [node]        SMOKE_KEEP=1 leaves the server up to poke at
#
# A green build proves this mod agrees with Anima's API. It cannot see a mixin that fails to apply,
# a registry that throws at bootstrap, a client-only class touched from common code (fine in dev,
# fatal on a dedicated server), a malformed data file, or a codec that loads "successfully" with
# every row silently dropped. Those are found by booting, and this is what boots.
#
# A REAL server from PUBLISHED jars, not a Loom dev run. This is the only thing that exercises the
# remapped shipping jars, Anima's jar-in-jar'd night-config, and the fabric.mod.json dependency
# between the two — the parts a player's game meets first and a dev classpath never has.
#
# The Anima it boots is staged by `:<node>:smokeMods`, resolved through the same coordinate and the
# same repositories as the compile, in the same Gradle run. So it is the library this was BUILT
# against, decided by the resolver rather than by a matching rule.
#
# ⚠ LOCALLY, PASS -PlocalMaven. `./gradlew -PlocalMaven=../.local-maven :<node>:smokeMods` is what
# stages the Anima the workspace just built; without it Gradle falls back to
# `../anima/build/local-maven`, which is whatever some earlier run left there. That is not
# hypothetical — it is how 26.2.x was first seen "failing to compile" against a library 17 hours
# stale (2026-08-18).
#
# CI runs this file rather than its own copy of the steps, so a red run is reproduced here in one
# command instead of by reading a workflow.
set -Eeuo pipefail

# `set -e` exits SILENTLY on a failed command substitution — which is how the sibling script in
# Anima died after printing nothing but its header. Name the line instead of leaving a bare exit
# code. (Commands in an if/while/until condition do not trigger this, which is what we want.)
trap 'echo "smoke.sh: failed at line $LINENO" >&2' ERR

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

die() { echo "$*" >&2; exit 1; }

NODE="${1:-$(grep -oP 'vcsVersion = "\K[^"]+' settings.gradle.kts)}"

# ── The pins, READ and never written ───────────────────────────────────────────────────────────
# Every version here comes out of the files the jars were built from. A smoke that carried its own
# copy of a version could boot a pairing the build never produced, and report green on it.
if [[ "$NODE" == snapshot ]]; then
    # The opt-in node: its pins live in the file scripts/snapshot.sh wrote, not in settings.
    SNAP=versions/snapshot/stonecutter.properties.toml
    [[ -f "$SNAP" ]] || die "no snapshot node — run scripts/snapshot.sh first"
    MC="$(sed -n 's/^mod\.minecraft = "\([^"]*\)".*/\1/p' "$SNAP")"
    FAPI="$(sed -n 's/^deps\.fabric_api = "\([^"]*\)".*/\1/p' "$SNAP")"
else
    MC="$(sed -n "s/^[[:space:]]*\"${NODE//./\\.}\" to \"\([^\"]*\)\",[[:space:]]*$/\1/p" settings.gradle.kts)"
    # Inside this node's own table.
    FAPI="$(awk -v s="[\"$NODE\"]" '$0==s{f=1;next} /^\[/{f=0} f && /^deps\.fabric_api/{sub(/.*= *"/,"");sub(/".*/,"");print;exit}' stonecutter.properties.toml)"
fi
[[ -n "$MC" ]] || die "no Minecraft version for node '$NODE'"
[[ -n "$FAPI" ]] || die "no deps.fabric_api for node '$NODE'"

# Top level of the TOML (before the first ["<node>"] table).
LOADER="$(awk '/^\[/{exit} /^deps\.fabric_loader/{sub(/.*= *"/,"");sub(/".*/,"");print;exit}' stonecutter.properties.toml)"
[[ -n "$LOADER" ]] || die "no deps.fabric_loader in stonecutter.properties.toml"

ANIMA_PIN="$(awk '/^\[/{exit} /^deps\.anima/{sub(/.*= *"/,"");sub(/".*/,"");print;exit}' stonecutter.properties.toml)"
[[ -n "$ANIMA_PIN" ]] || die "no deps.anima in stonecutter.properties.toml"

# ── The JVM, per node ──────────────────────────────────────────────────────────────────────────
# 26.x runs on 25, 1.21.x on 21. Getting this wrong is an UnsupportedClassVersionError a long way
# from anything that explains it. setup-java installs both and exports JAVA_HOME_<major>_X64;
# JAVA_HOME itself follows its LAST entry (25), so trusting that would boot 1.21.11 on the wrong
# JVM and blame the mod.
case "$MC" in
    26.*)   MAJOR=25 ;;
    1.21.*) MAJOR=21 ;;
    *)      MAJOR=17 ;;
esac
pick_java() {
    local m="$1" var home
    var="JAVA_HOME_${m}_X64"
    home="${!var:-}"
    if [[ -n "$home" && -x "$home/bin/java" ]]; then printf '%s/bin/java' "$home"; return 0; fi
    for home in "$HOME"/.jdks/*"$m"* /usr/lib/jvm/*"$m"*; do
        [[ -x "$home/bin/java" ]] && { printf '%s/bin/java' "$home"; return 0; }
    done
    # Captured, not piped: `java -version | head -1` SIGPIPEs java under pipefail, and the wrong
    # answer here is "no JVM found" on a machine that has one.
    if command -v java > /dev/null; then
        local banner; banner="$(java -version 2>&1 || true)"
        [[ "$banner" == *"\"$m"* ]] && { printf 'java'; return 0; }
    fi
    return 1
}
JAVA="$(pick_java "$MAJOR")" || die "no Java $MAJOR for Minecraft $MC (set JAVA_HOME_${MAJOR}_X64, or install it under ~/.jdks)"

SRV="${SMOKE_DIR:-build/smoke/$NODE}"
PORT="${SMOKE_PORT:-25569}"
LOG="$SRV/server.log"
FIFO="$SRV/stdin"
STAGED="versions/$NODE/build/smoke-mods"

echo "==> smoke: autarkia $NODE (Minecraft $MC, anima $ANIMA_PIN, loader $LOADER, fabric-api $FAPI, java $MAJOR)"

# ── The EULA ───────────────────────────────────────────────────────────────────────────────────
# The operator's legal choice, and no script's to make. CI records it in the workflow file, which
# is the repository owner saying so; a workstation writes the file once by hand.
mkdir -p "$SRV/mods"
if [[ "${SMOKE_EULA:-}" == "true" ]]; then
    echo 'eula=true' > "$SRV/eula.txt"
elif ! grep -qs '^eula=true' "$SRV/eula.txt"; then
    die "EULA not accepted ($SRV/eula.txt). Write it yourself, or set SMOKE_EULA=true if you accept https://aka.ms/MinecraftEULA."
fi

# ── The server ─────────────────────────────────────────────────────────────────────────────────
# Fabric's own launcher (~182 KB), which downloads Minecraft and its libraries on first boot. The
# installer version is whatever Fabric's meta lists first — it is their launcher plumbing, not a
# compatibility surface of ours, so pinning it here would only mean pinning it stale.
SERVER_JAR="$SRV/fabric-server-launch.jar"
if [[ ! -f "$SERVER_JAR" ]]; then
    # Matched in bash rather than through `grep | head | cut`: head exits on the first line, grep
    # takes SIGPIPE, and under `set -o pipefail` that fails the whole substitution silently.
    meta="$(curl -fsS --retry 3 https://meta.fabricmc.net/v2/versions/installer)"
    # The whitespace classes are not decoration: meta serves PRETTY-PRINTED json
    # (`"version": "1.1.2"`), and a pattern written against the compact form matches nothing.
    [[ "$meta" =~ \"version\"[[:space:]]*:[[:space:]]*\"([^\"]+)\" ]] \
        || die "could not read an installer version from meta.fabricmc.net"
    INSTALLER="${BASH_REMATCH[1]}"
    echo "==> fetching the Fabric server launcher (installer $INSTALLER)"
    curl -fsSL --retry 3 -o "$SERVER_JAR" \
        "https://meta.fabricmc.net/v2/versions/loader/$MC/$LOADER/$INSTALLER/server/jar"
fi

# ── mods/ ──────────────────────────────────────────────────────────────────────────────────────
# Two jars, and the run is meaningless with one: `:<node>:smokeMods` stages this mod beside the
# library it compiled against.
compgen -G "$STAGED/*.jar" > /dev/null \
    || die "nothing staged in $STAGED — run ./gradlew :$NODE:smokeMods first"
compgen -G "$STAGED/anima-*.jar" > /dev/null \
    || die "no anima jar in $STAGED — smokeMods resolved nothing; check GITEA_USER/GITEA_TOKEN, or pass -PlocalMaven"
rm -f "$SRV"/mods/*.jar
cp "$STAGED"/*.jar "$SRV/mods/"

# Fabric API is the one mod neither build produces. Both mods declare a hard `fabric-api`
# dependency and that id exists only in the AGGREGATE jar — the modules they compile against each
# carry their own — so Loader refuses to start without the umbrella.
#
# ONE copy, at THIS mod's pin, for both mods to run against. That is the player's situation, and it
# is what now exercises the promise that the two repos' `deps.fabric_api` agree.
curl -fsSL --retry 3 -o "$SRV/mods/fabric-api-$FAPI.jar" \
    "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$FAPI/fabric-api-$FAPI.jar"

echo "==> mods: $(ls "$SRV/mods" | tr '\n' ' ')"

# ── The world ──────────────────────────────────────────────────────────────────────────────────
# The quiet superflat: nothing generates, nothing spawns, nothing wanders into the assertions.
# pause-when-empty-seconds=-1 keeps it TICKING with no client attached, which is the only reason a
# headless check can observe a Person at all.
cat > "$SRV/server.properties" <<EOF
server-port=$PORT
online-mode=false
level-type=minecraft:flat
generator-settings={}
generate-structures=false
spawn-monsters=false
pause-when-empty-seconds=-1
motd=Autarkia smoke test
EOF

# ── Boot ───────────────────────────────────────────────────────────────────────────────────────
# A FRESH world every run, and the teardown of the last one: without this the save accumulates a
# CiProbe and a pair of strangers per run, and a check that names a Person rather than selecting one
# with `limit=1` goes ambiguous on the second run — which is a broken smoke, not a broken command:
# `as <name>` refuses to guess between two of a name. Removing it HERE rather than at the end leaves
# a failed run's world on disk to be poked at, and still guarantees the next run starts clean.
rm -rf "$SRV/world"

READY_TIMEOUT="${READY_TIMEOUT:-900}"
SRVPID=
AWAITED=0

# Block until a line matching $1 is in the current log, or die after $2 seconds saying the server
# never got round to $3. The wait is left in AWAITED rather than printed, because a caller reading
# it out of a command substitution would run the whole loop in a subshell — where `die` does not
# stop the script.
await() {
    AWAITED=0
    until grep -qaE "$1" "$LOG"; do
        kill -0 "$SRVPID" 2>/dev/null || die "the server exited before it could $3"
        (( AWAITED < $2 )) || die "the server did not $3 within ${2}s"
        sleep 2
        AWAITED=$((AWAITED + 2))
        (( AWAITED % 30 )) || echo "    still waiting for it to $3 (${AWAITED}s of ${2}s)"
    done
}

# Start the server on a fresh pipe and wait for it to be usable. A FUNCTION because the persistence
# proof at the end boots a SECOND time onto the same world — each boot writes its own log, so the
# readiness grep and the failure scan can never match the previous boot's lines.
boot() {
    LOG="$1"
    rm -f "$FIFO"
    mkfifo "$FIFO"
    : > "$LOG"
    ( cd "$SRV" && exec "$JAVA" -Xms2G -Xmx2G -jar "$(basename "$SERVER_JAR")" nogui ) \
        < "$FIFO" > "$LOG" 2>&1 &
    SRVPID=$!
    # Hold the write end open for as long as this server runs. Without it the server reads EOF the
    # moment the first command is delivered and shuts itself down mid-check.
    exec 3> "$FIFO"
    echo "==> booting in $SRV on port $PORT (log: $(basename "$LOG")) — a cold run downloads Minecraft first"
    await 'Done \(|For help, type' "$READY_TIMEOUT" 'report ready'
    echo "==> ready after ${AWAITED}s"
    # "Done" is vanilla's word, and it is not this pair's. StoreGuard checks every persisted store
    # on SERVER_STARTED, which on a world with entities to load lands TEN SECONDS after it. Reading
    # the guard's own line before then loses a race that does not look like one — which is exactly
    # how the persistence proof below first failed, silently, on a world that had saved perfectly.
    await 'stores checked' 120 'check its stores'
}

# Stop the server and WAIT for it to be gone — the world has to be fully written before anything
# reboots onto it, or the persistence proof reads a save the last run was still flushing.
halt() {
    printf 'stop\n' >&3 2>/dev/null || true
    exec 3>&- 2>/dev/null || true
    local w=0
    while kill -0 "$SRVPID" 2>/dev/null && (( w < 120 )); do sleep 1; w=$((w+1)); done
    ! kill -0 "$SRVPID" 2>/dev/null || die "the server did not shut down within 120s"
    SRVPID=
}

cleanup() {
    local rc=$?
    if (( rc )); then
        echo
        echo "--- last 60 lines of $LOG ---"
        tail -n 60 "$LOG" 2>/dev/null || true
    fi
    if [[ -z "${SMOKE_KEEP:-}" ]] && [[ -n "$SRVPID" ]] && kill -0 "$SRVPID" 2>/dev/null; then
        printf 'stop\n' >&3 2>/dev/null || true
        local w=0
        while kill -0 "$SRVPID" 2>/dev/null && (( w < 60 )); do sleep 1; w=$((w+1)); done
        kill -9 "$SRVPID" 2>/dev/null || true
    fi
    exec 3>&- 2>/dev/null || true
    exit $rc
}
trap cleanup EXIT

boot "$SRV/server.log"

# ── Did the PAIR load, and was it the right library? ───────────────────────────────────────────
# Loader lists every mod it accepted, with versions. A mismatch here means `smokeMods` staged
# something other than the pin this was compiled against, which would make everything below a check
# of the wrong pairing.
LOADED_ANIMA="$(grep -aoE '[[:space:]]anima [^[:space:],]+' "$LOG" | head -1 | awk '{print $2}' || true)"
[[ -n "$LOADED_ANIMA" ]] || die "Loader never listed anima — the library did not load beside this mod"
[[ "$LOADED_ANIMA" == "$ANIMA_PIN" ]] \
    || die "booted anima $LOADED_ANIMA but this was built against $ANIMA_PIN"
echo "==> loaded anima $LOADED_ANIMA (pin: $ANIMA_PIN)"

# ── Driving the console ────────────────────────────────────────────────────────────────────────
# Every console command "succeeds" whether or not it did anything — the server answers on its
# console, not through an exit status. So every command asserts on what came back, and this is the
# floor under all of them: Brigadier answers a command it does not RECOGNISE with a parse error,
# which without this check reads exactly like a feature that broke. The job this replaces spent
# three weeks red for that reason (`person needs` became `needs` when the needs roster landed, and
# what it printed was "the Person exists but is not ticking").
#
# The reply lands in MC_OUT rather than on stdout: a caller writing `out=$(mc …)` runs the whole
# function in a subshell, where `die` leaves the script running.
CMD_TIMEOUT="${CMD_TIMEOUT:-15}"
MC_OUT=""
mc() {
    local cmd="$1" mark="smoke-fence-$$-$SECONDS" from w=0
    from=$(( $(wc -l < "$LOG") + 1 ))
    printf '%s\n' "$cmd" >&3
    # A fence, so we read this command's reply and not a tick of somebody else's logging. `say`
    # with nobody online goes to the console only.
    printf 'say %s\n' "$mark" >&3
    # Process substitution, not a pipe: `grep -q` exits on the first match and SIGPIPEs a still-
    # writing tail, which under pipefail reports "not found" for a fence that IS there — flaky
    # while the log is small enough to fit the pipe buffer, then permanently wrong.
    until grep -qaF "$mark" < <(tail -n +"$from" "$LOG"); do
        (( w < CMD_TIMEOUT )) || die "no console answer to \`$cmd\` within ${CMD_TIMEOUT}s"
        sleep 1
        w=$((w + 1))
    done
    # `|| true`: a command that prints nothing leaves grep -v with no lines and an exit of 1, which
    # would fail the run for a command that did exactly what was asked.
    MC_OUT="$(tail -n +"$from" "$LOG" | grep -avF "$mark" || true)"
    echo "$MC_OUT"
    if grep -qE 'Unknown or incomplete command|Incorrect argument for command' <<< "$MC_OUT"; then
        die "the server did not understand \`$cmd\` — the command tree moved under this check"
    fi
}

# Forceload FIRST. With no player online nothing holds a chunk, so a Person spawned into one exists
# in the save and is not even selectable, let alone ticking — the rehearsal for this reported
# "Spawned CiProbe" and "No Persons are loaded" in the same breath. Stage inside the rectangle.
mc "forceload add -64 -64 64 64"
# And the strip the PAIR stands in. They are staged at z≈48, sixteen blocks off this rectangle's
# far edge, and a body that wanders over it stops ticking mid-conversation — a flake that would
# depend on which way the random walk went. Two commands rather than one wide one: forceload caps
# at 256 chunks per call, and 64 each leaves that headroom obvious.
mc "forceload add -64 64 64 192"
mc "autarkia spawn CiProbe"

# Spawning exercises entity registration, the identity directory, the appearance roll and the brain
# driver's first tick; whois reads it back out through the command tree.
mc 'autarkia whois @e[type=autarkia:person,name="CiProbe",limit=1]'
grep -q 'CiProbe' <<< "$MC_OUT" || die "the Person spawned but whois cannot find it"

# `needs` reads food and saturation off the live body, so it can only answer once the Person is
# actually TICKING — which half a boot on its own does not prove.
mc 'execute as @e[type=autarkia:person,name="CiProbe",limit=1] run autarkia needs'

# The same readout through the subject prefix. Both paths must work: `execute as` is what stamps
# a reply per line, and `as` is what an operator types.
mc 'autarkia as CiProbe needs'
grep -q 'food' <<< "$MC_OUT" || die "the Person exists but is not ticking"

# A server can reach "Done" and still have logged something that bites later — a failed datapack
# load, a codec that dropped rows, an entity that threw on its first tick.
#
# The exclusion is not laziness: a clean boot logs FabricLoader/Mixin WARNs for `ca/spottedleaf/…`
# because Mixin PROBES for optional Starlight integration that is not installed. Without it this
# arm fails every green run, which is how a check teaches people to ignore it. Scoped to the
# probe's own wording, so a real missing class still fails.
#
# A function because the persistence proof boots a second time, and a second boot is exactly where
# a store that cannot read its own file throws.
assert_clean_log() {
    if grep -naE 'Mixin apply failed|ClassNotFoundException|NoSuchMethodError|Failed to load|refusing to run' "$LOG" \
            | grep -vE 'Error loading class: (ca/spottedleaf|me/jellysquid)'; then
        die "the server logged the failure(s) above"
    fi
}
assert_clean_log

# ── Two strangers meet ─────────────────────────────────────────────────────────────────────────
# Everything above proves ONE body. This proves the thing two bodies do on their own: notice each
# other, walk over, exchange names, and part — with the transcript still on disk afterwards.
#
# CiProbe is muted first. It stands 49 blocks off, past both sight (senses.radius 24) and earshot
# (senses.hearing_radius 12), but its wander is a random walk rather than a leash and three minutes
# of drift is enough to put a third minded body in the pair's percepts — which would make "who did
# Alma go and talk to" a coin toss. `brain auto false` stops the arbiter without destroying it.
echo
echo "==> the scene: two strangers, twenty blocks apart"
mc 'autarkia as CiProbe brain auto false'

# Twenty blocks apart: inside sight, outside earshot, so the approach is a real HAIL rather than a
# walk over to somebody already audible. Positioned RELATIVE to the console, which stands on the
# world spawn — the same cell CiProbe spawned into — so the scene never has to guess at the ground.
mc 'autarkia spawn ~-10 ~ ~48 Alma'
mc 'autarkia spawn ~10 ~ ~48 Bram'

# Turned to face each other, and NOT for the look of it: an idle head scans 100° off the SHOULDERS
# inside a 150° cone, and the shoulders only move when the body walks. Two bodies spawned staring
# the same way are two bodies that cannot see each other, and the first run of this scene spent 143
# seconds of wandering before either happened to turn far enough — a coin toss this check would
# rather not be deciding. What is proved below starts once they HAVE noticed each other.
mc 'tp @e[type=autarkia:person,name="Alma",limit=1] ~-10 ~ ~48 facing entity @e[type=autarkia:person,name="Bram",limit=1]'
mc 'tp @e[type=autarkia:person,name="Bram",limit=1] ~10 ~ ~48 facing entity @e[type=autarkia:person,name="Alma",limit=1]'

# The loneliness is SET, not waited for. A settler's solitude drain crosses the whole gauge in
# social.company_solitude_ticks (48,000 — two in-game days), so a smoke that waited for the mood it
# is testing would take an hour. This is the one thing staged by hand; everything after it is the
# brain's own decision.
mc 'autarkia as Alma needs company 0'
mc 'autarkia as Bram needs company 0'

# Two finish lines, waited on together.
#
# The contact books are the strictest statement of the first: a name only lands in ContactData when
# an `inform_name` is actually spoken into a live encounter, so BOTH books knowing the other body
# is the whole chain — perceive, hail, walk, greet, ask, answer — in one assertion.
#
# `end_chat` is the second, and it is not decoration either. It is the ONLY act that closes a
# record, so without it a conversation is a thing that starts and never stops: the pair went on
# proposing to leave, one line each per tick, into a store that persists every line. That is what
# this arm caught, and what it now keeps caught.
#
# Polled rather than slept on, because how long any of it takes is a tuning question.
SCENE_TIMEOUT="${SCENE_TIMEOUT:-180}"
echo "==> waiting for them to meet, introduce themselves and part (up to ${SCENE_TIMEOUT}s)"
done_talking=
waited=0
while (( waited < SCENE_TIMEOUT )); do
    sleep 5
    waited=$((waited + 5))
    mc 'autarkia as Alma contacts' > /dev/null
    alma_knows="$MC_OUT"
    mc 'autarkia as Bram contacts' > /dev/null
    bram_knows="$MC_OUT"
    mc 'autarkia as Alma log brain 80' > /dev/null
    talk="$MC_OUT"
    mc 'autarkia as Bram log brain 80' > /dev/null
    talk="$talk"$'\n'"$MC_OUT"
    if grep -qaF 'Bram' <<< "$alma_knows" && grep -qaF 'Alma' <<< "$bram_knows" \
            && grep -qaF 'said end_chat' <<< "$talk"; then
        done_talking=yes
        break
    fi
    echo "    not yet (${waited}s of ${SCENE_TIMEOUT}s)"
done
if [[ -z "$done_talking" ]]; then
    # The journals are the diagnosis: they say whether nobody was perceived, whether the walk never
    # arrived, whether they stood in front of each other with nothing to say — or whether they are
    # still going, which is its own bug and looks nothing like the others.
    mc 'autarkia as Alma log brain 40'
    mc 'autarkia as Bram log brain 40'
    die "they did not meet, introduce themselves and part within ${SCENE_TIMEOUT}s"
fi
echo "==> they met, introduced themselves and parted after ${waited}s"

# Knowing a name proves the introduction. These prove the CONVERSATION around it: a line this body
# chose and said, and the contact book gaining a row it did not have. Both sides, because one body
# doing all the talking is a different bug from neither doing any.
for who in Alma Bram; do
    mc "autarkia as $who log brain 60"
    grep -qaE ' - converse - said ' <<< "$MC_OUT" \
        || die "$who's journal records no line SAID — they met without conversing"
    grep -qaF 'learned their name' <<< "$MC_OUT" \
        || die "$who's journal never records learning a name"
done

# Company is what the whole errand was for, and the one number that says the machinery PAID. Both
# were set to a hard 0 above and solitude only ever drains, so anything above the floor came from
# the meeting and the lines exchanged.
for who in Alma Bram; do
    mc "autarkia as $who needs"
    company="$(awk '{for (i = 1; i < NF; i++) if ($i == "company" && $(i + 1) ~ /^[0-9.]+$/) {
                        print $(i + 1); exit } }' <<< "$MC_OUT")"
    [[ -n "$company" ]] || die "could not read $who's company gauge out of \`needs\`"
    awk -v v="$company" 'BEGIN { exit !(v > 0.0) }' \
        || die "$who's company is still on the floor ($company) — nothing paid the gauge"
    echo "==> $who: company $company"
done

# ── The transcript outlives the server ─────────────────────────────────────────────────────────
# An encounter is world state, and the only proof of that is a world that has been closed and
# reopened. StoreGuard runs on SERVER_STARTED and THROWS when a store's file is on disk and comes
# back empty or short, so simply reaching "ready" a second time is the clean-boot half; the row
# count is the part that says the conversation itself is what survived.
echo
echo "==> restarting onto the same world"
mc 'save-all flush'
halt
boot "$SRV/server-restart.log"
assert_clean_log

guard="$(grep -a 'stores checked' "$LOG" | tail -n 1)"
[[ "$guard" =~ encounters\ on\ disk\ v([0-9]+)\ ([0-9]+)\ row ]] \
    || die "the boot guard did not report the encounters store as loaded from disk: $guard"
(( BASH_REMATCH[2] > 0 )) \
    || die "the encounters store came back with no rows — the transcript did not persist"
echo "==> encounters store: v${BASH_REMATCH[1]}, ${BASH_REMATCH[2]} row(s) read back off disk"

# And the acquaintance with it — a different store, written by the same conversation.
mc 'autarkia as Alma contacts'
grep -qaF 'Bram' <<< "$MC_OUT" || die "Alma forgot Bram across the restart"

echo
echo "SMOKE OK — autarkia $NODE on Minecraft $MC with anima $LOADED_ANIMA: a Person spawned, ticked"
echo "and answered; two strangers met, introduced themselves, parted, and their conversation was still"
echo "on disk when the world was reopened."
