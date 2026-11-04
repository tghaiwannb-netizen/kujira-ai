#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if command -v gradle >/dev/null 2>&1; then exec gradle "$@"; fi
wrapper="$HOME/.gradle/wrapper/dists/gradle-9.6.1-bin"
if [ -d "$wrapper" ]; then
  candidate=$(find "$wrapper" -path '*/gradle-9.6.1/bin/gradle' -type f | head -n 1)
  if [ -n "$candidate" ]; then exec "$candidate" "$@"; fi
fi
echo "Gradle 9.6.1 not found; install Gradle and Android SDK." >&2
exit 1
