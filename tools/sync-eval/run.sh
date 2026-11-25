#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
kotlinc app/src/main/java/com/example/webedge/SyncData.kt tools/sync-eval/Entry.kt tools/sync-eval/Test.kt -include-runtime -d "$TMP/test.jar"
java -jar "$TMP/test.jar"
