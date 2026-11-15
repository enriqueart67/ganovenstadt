#!/usr/bin/env bash
# Builds PeregrineGrant.jar (Paper / Spigot / Purpur plugin). Needs a JDK 17+.
# The Bukkit classes in stubs/ are only there to compile against – the server provides the real ones.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build && mkdir -p build/stubs build/classes
javac --release 17 -d build/stubs $(find stubs -name '*.java')
javac --release 17 -cp build/stubs -d build/classes $(find src -name '*.java')
cp src/plugin.yml build/classes/
(cd build/classes && jar cf ../PeregrineGrant.jar .)
mkdir -p ../downloads && cp build/PeregrineGrant.jar ../downloads/PeregrineGrant.jar
ls -la ../downloads/PeregrineGrant.jar
