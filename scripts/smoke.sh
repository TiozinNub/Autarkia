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
MC="$(sed -n "s/^[[:space:]]*\"${NODE//./\\.}\" to \"\([^\"]*\)\",[[:space:]]*$/\1/p" settings.gradle.kts)"
[[ -n "$MC" ]] || die "no Minecraft version for node '$NODE' in settings.gradle.kts"

# Top level of the TOML (before the first ["<node>"] table).
LOADER="$(awk '/^\[/{exit} /^deps\.fabric_loader/{sub(/.*= *"/,"");sub(/".*/,"");print;exit}' stonecutter.properties.toml)"
[[ -n "$LOADER" ]] || die "no deps.fabric_loader in stonecutter.properties.toml"

ANIMA_PIN="$(awk '/^\[/{exit} /^deps\.anima/{sub(/.*= *"/,"");sub(/".*/,"");print;exit}' stonecutter.properties.toml)"
[[ -n "$ANIMA_PIN" ]] || die "no deps.anima in stonecutter.properties.toml"

# Inside this node's own table.
FAPI="$(awk -v s="[\"$NODE\"]" '$0==s{f=1;next} /^\[/{f=0} f && /^deps\.fabric_api/{sub(/.*= *"/,"");sub(/".*/,"");print;exit}' stonecutter.properties.toml)"
[[ -n "$FAPI" ]] || die "no deps.fabric_api for [$NODE] in stonecutter.properties.toml"

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
rm -f "$FIFO"
mkfifo "$FIFO"
: > "$LOG"
( cd "$SRV" && exec "$JAVA" -Xms2G -Xmx2G -jar "$(basename "$SERVER_JAR")" nogui ) < "$FIFO" > "$LOG" 2>&1 &
SRVPID=$!
# Hold the write end open for the whole run. Without this the server reads EOF the moment the first
# command is delivered and shuts itself down mid-check.
exec 3> "$FIFO"

cleanup() {
    local rc=$?
    if (( rc )); then
        echo
        echo "--- last 60 lines of $LOG ---"
        tail -n 60 "$LOG" 2>/dev/null || true
    fi
    if [[ -z "${SMOKE_KEEP:-}" ]] && kill -0 "$SRVPID" 2>/dev/null; then
        printf 'stop\n' >&3 2>/dev/null || true
        local w=0
        while kill -0 "$SRVPID" 2>/dev/null && (( w < 60 )); do sleep 1; w=$((w+1)); done
        kill -9 "$SRVPID" 2>/dev/null || true
    fi
    exec 3>&- 2>/dev/null || true
    exit $rc
}
trap cleanup EXIT

READY_TIMEOUT="${READY_TIMEOUT:-900}"
echo "==> booting in $SRV on port $PORT"
waited=0
until grep -qaE 'Done \(|For help, type' "$LOG"; do
    kill -0 "$SRVPID" 2>/dev/null || die "the server exited before it reported ready"
    (( waited < READY_TIMEOUT )) || die "the server did not report ready within ${READY_TIMEOUT}s"
    sleep 2
    waited=$((waited + 2))
    (( waited % 30 )) || echo "    still booting (${waited}s of ${READY_TIMEOUT}s) — a cold run downloads Minecraft first"
done
echo "==> ready after ${waited}s"

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
if grep -naE 'Mixin apply failed|ClassNotFoundException|NoSuchMethodError|Failed to load|refusing to run' "$LOG" \
        | grep -vE 'Error loading class: (ca/spottedleaf|me/jellysquid)'; then
    die "the server booted but logged the failure(s) above"
fi

echo
echo "SMOKE OK — autarkia $NODE on Minecraft $MC with anima $LOADED_ANIMA: a Person spawned, ticked and answered."
