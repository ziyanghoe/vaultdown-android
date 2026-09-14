#!/usr/bin/env bash
# Generate the official Gradle wrapper with an installed Gradle 8.9 distribution.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if ! command -v gradle >/dev/null 2>&1; then
    echo 'Install Gradle 8.9 and JDK 17 first: https://gradle.org/install/' >&2
    exit 1
fi
wrapper_work="$(mktemp -d)"
trap 'rm -rf -- "$wrapper_work"' EXIT
touch "$wrapper_work/settings.gradle"
gradle --no-daemon -p "$wrapper_work" wrapper --gradle-version 8.9 --distribution-type bin
cp "$wrapper_work/gradlew" "$wrapper_work/gradlew.bat" "$project_dir/"
mkdir -p "$project_dir/gradle"
cp -R "$wrapper_work/gradle/wrapper" "$project_dir/gradle/"
chmod +x "$project_dir/gradlew"
echo 'Official wrapper generated. Run ./gradlew :core:check :app:assembleDebug :app:lintDebug'
