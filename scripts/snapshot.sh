#!/usr/bin/env bash
# Opt this checkout into a Minecraft version that is not a target yet — Mojang's newest build, by
# default — as the `snapshot` Stonecutter node.
#
# Usage: scripts/snapshot.sh [latest|off|<minecraft version>]
#
# Writes versions/snapshot/stonecutter.properties.toml, which git ignores and settings.gradle.kts
# turns into the node; from then on `./gradlew :snapshot:build` works like any other node's.
# `off` removes it. The committed node set never changes: a checkout without the file has no
# such node, and a checkout with it has one nobody can commit.
#
# THE PINS ARE RESOLVED, NEVER WRITTEN DOWN. Fabric meta says which game versions exist and which
# of them Loader boots; Modrinth says which Fabric API was built for one. A version carried in this
# file would be stale the week after it was typed, and the week after is the whole point.
#
# Exit codes, because CI acts on them:
#   0  the node is written
#   3  nothing to do — the newest build is already a committed target
#   4  the build exists but cannot be tried yet: no Loader for it, no Fabric API for it, or a Java
#      the toolchain map does not know. Try again later; nothing here is wrong.
#   anything else is this script failing.
set -Eeuo pipefail
trap 'echo "snapshot.sh: failed at line $LINENO" >&2' ERR

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

NODE_DIR=versions/snapshot
NODE_FILE="$NODE_DIR/stonecutter.properties.toml"
WANT="${1:-latest}"

if [[ "$WANT" == off ]]; then
    rm -rf "$NODE_DIR"
    echo "snapshot node removed"
    exit 0
fi

mkdir -p "$NODE_DIR"
# One Python program rather than curl-and-sed: four JSON APIs and a version grammar. It writes
# the file itself and prints one summary line; its exit code is the verdict.
rc=0
python3 - "$WANT" "$NODE_FILE" <<'PY' || rc=$?
import datetime, json, os, re, sys, urllib.parse, urllib.request

want, node_file = sys.argv[1], sys.argv[2]
UA = "autarkia/snapshot.sh (modrinth.com/mod/autarkia)"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def verdict(code, msg):
    print(f"snapshot.sh: {msg}", file=sys.stderr)
    sys.exit(code)


# The committed targets, off the same lines settings.gradle.kts builds its nodes from.
settings = open("settings.gradle.kts").read()
targets = dict(re.findall(r'^\s*"([^"]+)" to "([^"]+)",\s*$', settings, re.M))
committed = set(targets.values())

game = get("https://meta.fabricmc.net/v2/versions/game")  # newest first
known = [g["version"] for g in game]
if want == "latest":
    mc = known[0]
    if mc in committed:
        print(f"nothing to do: the newest build Fabric knows, {mc}, is already a target")
        sys.exit(3)
else:
    mc = want
    if mc not in known:
        verdict(2, f"{mc} is not a Minecraft version Fabric meta lists")

# Fabric Loader's own grammar for the 26.x names, and its own normalisation: 26.4-snapshot-3 is
# 26.4-alpha.3, -pre-1 is -pre.1, -rc-1 is -rc.1. Alphabetical order is release order, and a
# prerelease sorts below its release, which is what lets `>=26.4-alpha` mean "26.4 and the road
# to it" in a Stonecutter condition.
m = re.fullmatch(r"(\d{2}\.\d+(?:\.\d+)?)(?:-(snapshot|pre|rc)-(\d+))?", mc)
if not m:
    verdict(4, f"{mc} is not a version shape this script knows (26.4, 26.4-snapshot-3, 26.4-pre-1, 26.4-rc-1)")
base, kind, n = m.groups()
semver = base if kind is None else f"{base}-{'alpha' if kind == 'snapshot' else kind}.{n}"
line = ".".join(base.split(".")[:2])

loaders = [l["loader"]["version"] for l in get(f"https://meta.fabricmc.net/v2/versions/loader/{urllib.parse.quote(mc)}")]
if not loaders:
    verdict(4, f"Fabric Loader has no build for {mc} yet")
toml = open("stonecutter.properties.toml").read()
pinned_loader = re.search(r'^deps\.fabric_loader = "([^"]+)"', toml, re.M).group(1)
if pinned_loader not in loaders:
    verdict(4, f"Fabric Loader {pinned_loader} (deps.fabric_loader) does not boot {mc}; the newest that does is {loaders[0]} — bump the pin first")

# The JVM. Every 26.x has run on 25, and three places say so: requiredJava in build.gradle.kts,
# the case in scripts/smoke.sh, and setup-java in the workflows. A build that moves is a build
# to teach them about, not one to try blind.
manifest = get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
entry = next((v for v in manifest["versions"] if v["id"] == mc), None)
if entry is None:
    verdict(4, f"Mojang's manifest does not list {mc} yet")
java = get(entry["url"])["javaVersion"]["majorVersion"]
if java != 25:
    verdict(4, f"{mc} runs on Java {java}; requiredJava, scripts/smoke.sh and the workflows assume 25 for 26.x — teach them first")


def modrinth(project, game_versions):
    q = {"loaders": '["fabric"]'}
    if game_versions:
        q["game_versions"] = json.dumps(game_versions)
    return get(f"https://api.modrinth.com/v2/project/{project}/version?{urllib.parse.urlencode(q)}")  # newest first


fapi = modrinth("fabric-api", [mc])
if not fapi:
    verdict(4, f"Fabric API has no build for {mc} yet")
fapi = fapi[0]["version_number"]


def committed_pin(key):
    """`key` from the table of the newest committed target, by Minecraft version."""
    newest = max(targets.items(), key=lambda kv: tuple(int(p) for p in kv[1].split("-")[0].split(".")))[0]
    table = re.search(r'^\["' + re.escape(newest) + r'"\]\n(.*?)(?=^\[|\Z)', toml, re.M | re.S).group(1)
    return re.search(r'^' + re.escape(key) + r' = "([^"]+)"', table, re.M).group(1)

stamp = datetime.date.today().isoformat()
lines = [
    f"# The snapshot node — written by scripts/snapshot.sh on {stamp}, never committed.",
    f"# Minecraft {mc}, which Fabric Loader calls {semver}. Remove with `scripts/snapshot.sh off`.",
    f'snapshot.semver = "{semver}"',
    f'mod.minecraft = "{mc}"',
    # Fabric API's own predicate for a line and its prereleases: `~26.4-` reads 26.4-alpha.1 and
    # 26.4 and 26.4.1 alike.
    f'mod.mc_compat = "~{line}-"',
    f'mod.mc_releases = ["{mc}"]',
    "",
    f'deps.fabric_api = "{fapi}"',
]
summary = f"snapshot node: Minecraft {mc} ({semver}), Fabric API {fapi}, Java {java}"

with open(node_file, "w") as f:
    f.write("\n".join(lines) + "\n")
print(summary)

# For the workflow, which acts on what was picked.
out = os.environ.get("GITHUB_OUTPUT")
if out:
    with open(out, "a") as f:
        f.write(f"minecraft={mc}\nsemver={semver}\nfabric_api={fapi}\n")
PY

if (( rc != 0 )); then
    # Never leave a half-written node behind: a directory with no file is still a directory the
    # settings script would not turn into a node, but a stale file from an earlier run would be.
    rm -rf "$NODE_DIR"
    exit "$rc"
fi
