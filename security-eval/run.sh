#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
java com.sun.tools.javac.Main --release 17 -d "$classes" app/src/main/java/com/example/webedge/WebOrigin.java app/src/main/java/com/example/webedge/BridgeTouch.java security-eval/*Test.java
java -cp "$classes" com.example.webedge.WebOriginTest
java -cp "$classes" com.example.webedge.BridgeTouchTest
python3 security-eval/SourcePolicyTest.py
