#!/bin/sh
# usage: tools/autotest/run.sh <script> <log>   (hidden window, muted; screenshots in run-client/screenshots)
cd "$(dirname "$0")/../.."
./gradle.sh runClient -PtbRunDir=run-client -PtbAutotest="$(cygpath -m "$PWD/$1" 2>/dev/null || echo "$PWD/$1")" > "$2" 2>&1
