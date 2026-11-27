#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
src=app/src/main/java/com/example/webedge
kotlinc "$src/RecordCipher.kt" "$src/RecordWritePolicy.kt" security-eval/RecordWritePolicyTest.kt -include-runtime -d "$out/policy.jar"
java -jar "$out/policy.jar"
