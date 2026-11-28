#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
src=app/src/main/java/com/example/webedge
kotlinc "$src/ThumbnailCipher.kt" "$src/RecoveryReminderPolicy.kt" security-eval/RecoveryR2Test.kt -include-runtime -d "$out/r2.jar"
java -jar "$out/r2.jar"
python3 security-eval/RecoveryR2PolicyTest.py
