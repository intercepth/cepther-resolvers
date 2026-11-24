#!/usr/bin/env bash
# Dependency-free build + offline test run. Needs a JDK (11+). Compiles with --release 11 so the sources
# stay usable on older toolchains.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
javac --release 11 -Xlint:all -d out $(find src -name '*.java')
if [ "${1:-}" != "--no-test" ]; then
  java -cp out com.cepther.resolvers.TestMain ../manifest.json
fi
