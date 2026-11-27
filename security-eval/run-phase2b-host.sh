#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
src=app/src/main/java/com/example/webedge
kotlin_home="$(cd "$(dirname "$(readlink -f "$(command -v kotlinc)")")/.." && pwd)"
coroutines="${KOTLIN_COROUTINES_JAR:-$kotlin_home/lib/kotlinx-coroutines-core-jvm.jar}"
kotlinc -cp "$coroutines" "$src/ProtectedRecords.kt" "$src/RecordWritePolicy.kt" "$src/RecordCipher.kt" "$src/SecurityEnvelope.kt" security-eval/phase2b-host/*.kt -include-runtime -d "$out/host.jar"
for scenario in migrate commit-before commit-after key-loss journal-corrupt preflight-invalid binding orphan concurrent; do
    java -cp "$out/host.jar:$coroutines" com.example.webedge.ProtectedRecordsHostTestKt "$scenario"
done
