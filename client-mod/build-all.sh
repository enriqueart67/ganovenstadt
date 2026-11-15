#!/usr/bin/env bash
# Baut die Client-Mod fuer alle unterstuetzten Minecraft-Versionen und kopiert sie in den Launcher.
# Braucht ein JDK 25 (JAVA_HOME). Neue Version: Zeile mit Minecraft- und Fabric-API-Version ergaenzen.
set -euo pipefail
cd "$(dirname "$0")"
OUT=../launcher/assets/client-mod
mkdir -p "$OUT"
while read -r mc api; do
  [ -z "$mc" ] && continue
  series=$(echo "$mc" | cut -d. -f1-2)
  echo "== Minecraft $mc (Reihe $series)"
  ./gradlew build --console=plain -q -Pminecraft_version="$mc" -Pfabric_api_version="$api" || { echo "FAILED $mc"; exit 1; }
  cp "build/libs/peregrine-client-1.0.0+$mc.jar" "$OUT/peregrine-client-$series.jar"
done <<'LIST'
26.1.2 0.155.3+26.1.2
26.2   0.161.0+26.2
26.3   0.166.2+26.3
LIST
# libraries for the assistant's Claude mode – one jar for every version
(cd ai-libs && ../gradlew build --console=plain -q) || { echo "FAILED ai-libs"; exit 1; }
cp ai-libs/build/libs/peregrine-ai-libs-*.jar "$OUT/peregrine-ai-libs.jar"
ls -la "$OUT"
