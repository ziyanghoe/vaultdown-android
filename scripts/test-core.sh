#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
test_output="$(mktemp -d)"
trap 'rm -rf -- "$test_output"' EXIT
mapfile -t source_files < <(find "$project_dir/core/src" -name '*.java' -print)
java --module jdk.compiler/com.sun.tools.javac.Main --release 17 -encoding UTF-8 -d "$test_output" "${source_files[@]}"
java -cp "$test_output" dev.vaultdown.core.CoreTests
