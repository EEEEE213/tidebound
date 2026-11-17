#!/bin/sh
# Publish a Tidebound release that every installed copy picks up through the in-game updater.
#   tools/release.sh <version> ["release notes"]
set -e
cd "$(dirname "$0")/.."
V="$1"; NOTES="${2:-Tidebound $V}"
[ -n "$V" ] || { echo "usage: tools/release.sh <version> [notes]"; exit 1; }
sed -i "s/^mod_version=.*/mod_version=$V/" gradle.properties
./gradle.sh build
JAR="dist/tidebound-1.21.1-neoforge-$V.jar"
cp "build/libs/tidebound-$V.jar" "$JAR"
(cd modpack && python build.py resolved.json "../$JAR" "../dist/Tidebound-$V.mrpack" cache "$V")
git add -A
git commit -q -m "Release $V"
git tag "v$V"
git push -q origin HEAD --tags
gh release create "v$V" "$JAR" "dist/Tidebound-$V.mrpack" --title "Tidebound $V" --notes "$NOTES"
