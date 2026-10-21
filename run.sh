#!/bin/bash
# GAP Aviation: Golden Aerotransport (1.20.1 port) + GAP Cabin Walk + IV Autopilot.
#
#   ./run.sh              start Minecraft with all three mods (and Immersive Vehicles)
#   ./run.sh --dh         ...also with Distant Horizons
#   ./run.sh build        build the release jars of all three mods into ./dist
#   ./run.sh <gradle args> anything else is passed to Gradle (e.g. ./run.sh -PdhVersion=3.2.0-b-1.20.1 --dh)
#
# The game itself (worlds, settings, logs, mods folder) lives in ./instance.
# Java 17 is picked automatically by each project's gradlew; your default Java is left alone.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
PROJECTS=(GoldenAeroTransport1.20.1 GAPCabinWalk1.20.1 IVAutopilot1.20.1)

if [[ "${1:-}" == "build" ]]; then
    shift
    mkdir -p "$ROOT/dist"
    for p in "${PROJECTS[@]}"; do
        echo "==> Building $p"
        (cd "$ROOT/$p" && ./gradlew build "$@")
        for jar in "$ROOT/$p"/build/libs/*.jar; do
            case "$jar" in *-sources.jar|*-dev*.jar|*-slim.jar) continue ;; esac
            cp "$jar" "$ROOT/dist/"
        done
    done
    echo "==> Jars in $ROOT/dist:"
    ls -1 "$ROOT/dist"
    exit 0
fi

ARGS=()
for a in "$@"; do
    if [[ "$a" == "--dh" ]]; then ARGS+=("-PwithDH"); else ARGS+=("$a"); fi
done
# IV Autopilot's runClient builds the other two mods, copies them into instance/mods and starts the game.
cd "$ROOT/IVAutopilot1.20.1"
exec ./gradlew runClient ${ARGS[@]+"${ARGS[@]}"}
