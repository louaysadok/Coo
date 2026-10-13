#!/usr/bin/env bash
# Run the pure-Kotlin core tests on the JVM — no Android SDK required.
#
# It compiles the REAL app sources (app/src/main/java/com/ERT/app/core/*.kt) together with
# tools/tests/CoreTest.kt, so this exercises shipping code, not a copy of it.
#
# Requirements: a JDK and kotlinc. See tools/README.md for how to obtain both without
# dl.google.com (JDK from PyPI's jdk4py, kotlinc from npm's kotlin-compiler).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

JDK_HOME="${JDK_HOME:-/opt/venv/lib/python3.11/site-packages/jdk4py/java-runtime}"
KOTLINC="${KOTLINC:-/opt/kt/node_modules/kotlin-compiler/bin/kotlinc}"

if [[ ! -x "$JDK_HOME/bin/java" ]]; then
  echo "JDK not found at $JDK_HOME — set JDK_HOME=..." >&2
  exit 2
fi
if [[ ! -x "$KOTLINC" ]]; then
  echo "kotlinc not found at $KOTLINC — set KOTLINC=..." >&2
  exit 2
fi

OUT="${OUT:-/tmp/ert-core-tests}"
rm -rf "$OUT" && mkdir -p "$OUT"

echo "== compiling core + tests =="
export JAVA_HOME="$JDK_HOME"
export PATH="$JDK_HOME/bin:$PATH"
java -version 2>&1 | head -1

CORE=(
  "$ROOT/app/src/main/java/com/ERT/app/core/Json.kt"
  "$ROOT/app/src/main/java/com/ERT/app/core/Models.kt"
  "$ROOT/app/src/main/java/com/ERT/app/core/Url.kt"
  "$ROOT/app/src/main/java/com/ERT/app/core/Parser.kt"
  "$ROOT/app/src/main/java/com/ERT/app/core/Requests.kt"
)

"$KOTLINC" "${CORE[@]}" "$ROOT/tools/tests/CoreTest.kt" \
  -include-runtime -nowarn -d "$OUT/tests.jar"

echo "== running =="
java -cp "$OUT/tests.jar" CoreTest "$ROOT/fixtures"
