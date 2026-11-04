#!/bin/bash
# Local Gradle launcher for Kujira (no wrapper download needed).
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/Cellar/openjdk@21/21.0.8/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
GRADLE_BIN=$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-9.6.1-bin/*/gradle-9.6.1/bin/gradle 2>/dev/null | head -1)
if [ -z "$GRADLE_BIN" ]; then echo "gradle 9.6.1 dist not found"; exit 1; fi
cd "$(dirname "$0")" || exit 1
exec "$GRADLE_BIN" "$@"
