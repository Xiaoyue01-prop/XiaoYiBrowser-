#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
src=app/src/main/java/com/example/webedge
kotlin_home="$(cd "$(dirname "$(readlink -f "$(command -v kotlinc)")")/.." && pwd)"
coroutines="${KOTLIN_COROUTINES_JAR:-$kotlin_home/lib/kotlinx-coroutines-core-jvm.jar}"
kotlinc "$src/RecoveryArchive.kt" security-eval/RecoveryArchiveTest.kt -include-runtime -d "$out/archive.jar"
java -jar "$out/archive.jar"
kotlinc -cp "$coroutines" "$src/RecoveryArchive.kt" "$src/ProtectedRecords.kt" "$src/RecordWritePolicy.kt" "$src/RecordCipher.kt" "$src/SecurityEnvelope.kt" security-eval/phase2b-host/Fake*.kt security-eval/RecoveryHostTest.kt -include-runtime -d "$out/host.jar"
for scenario in plain-export key-loss-portable nonempty schema binding failed sync-unhealthy lost-source-key commit-before commit-after wrong-key; do
    java -cp "$out/host.jar:$coroutines" com.example.webedge.RecoveryHostTestKt "$scenario"
done
python3 security-eval/RecoveryPolicyTest.py
